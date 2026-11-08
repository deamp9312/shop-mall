# ADR-0002 트랜잭셔널 아웃박스 + 폴링 발행기

## 배경
"DB 커밋"과 "Kafka 발행"은 한 트랜잭션으로 묶이지 않는다. 커밋 후 발행하다 앱이 죽으면 이벤트가 **유실**되고,
발행 후 커밋이 실패하면 존재하지 않는 주문의 이벤트가 나간다. 주문·결제 이벤트가 하나라도 빠지면 재고 선점이 영원히 풀리지 않는다.

## 결정
- 서비스는 `KafkaTemplate.send` 를 직접 부르지 않는다. 도메인 변경과 **같은 트랜잭션**에서 `EventOutbox.record(event)` 로
  `outbox_events` 에 1행을 쓴다(`Propagation.MANDATORY` — 트랜잭션 밖에서 부르면 예외).
- `OutboxRelay` 가 500ms 마다 `SELECT ... FOR UPDATE SKIP LOCKED`(READ COMMITTED, 100건)로 PENDING 행을 id 순서로 보내고
  SENT 로 바꾼다. 인스턴스가 둘이어도 같은 행을 동시에 보내지 않는다. SENT 는 7일 뒤 삭제한다.
- 키 = orderId, Kafka 헤더 `traceId`·`eventId`·`eventType`. 생산자 `acks=all`, `enable.idempotence=true`.
- **브로커 장애 시 PENDING 유지** (3단계 결정): 타임아웃·재시도 가능 오류는 재시도 횟수에 넣지 않고 다음 폴링에 다시 보낸다.
  장애가 길어도 FAILED 로 떨어지지 않으므로 복구 후 자동으로 발행된다. 그 밖의 오류(직렬화 등 다시 보내도 같은 결과)는 5회째 FAILED.
  한 번의 전송은 `max.block.ms`·`delivery.timeout.ms` 10초 안에 포기하므로 발행기가 몇 분씩 묶이지 않는다.
- **FAILED 복구와 순서 (리뷰 2026-11-06 Medium-3)**: 같은 message_key(주문)에 앞선 FAILED 행이 있으면 뒤 PENDING 은 보류한다
  (`lockPending` 의 `NOT EXISTS`, 잠그는 행은 `FOR UPDATE OF o`). 한 배치 안에서 실패한 주문의 뒤 이벤트도 그 폴링에서는
  건너뛴다. 관리자는 `/admin/events` 의 [다시 발행](`POST /api/admin/outbox/{id}/requeue`, ADMIN·CSRF)으로 FAILED 를 PENDING
  (시도 0)으로 되돌리고, 보류 중인 후속 이벤트 수를 화면에서 본다. 되돌린 행은 id 가 작아 보류된 것보다 먼저 나간다.
- 발행기·시간 초과 잡·지연 승인이 스케줄러를 공유하므로 `spring.task.scheduling.pool.size=3` (5단계): 장애 중 발행기가
  묶여도 시간 초과 취소와 [응답 지연] 승인은 돈다.

## 결과
- 좋은 점: 유실 0 — 커밋된 변경은 반드시 발행된다(Kafka 정지 중 주문 → 재기동 후 발행 테스트).
- 나쁜 점: FAILED 하나가 그 주문의 뒤 이벤트를 관리자가 처리할 때까지 붙잡는다(순서를 유실·역전보다 우선). 발행이 최대 폴링 주기(500ms)만큼 늦다. ack 를 잃으면 같은 이벤트가 **두 번** 나갈 수 있다 → 소비 측 멱등이
  필수(ADR-0004). 같은 주문의 이벤트라도 토픽이 다르면 순서가 보장되지 않는다(예: order.cancelled 가 order.created 보다
  먼저 payment 에 도착) → 해당 컨슈머가 재시도로 기다린다(`PaymentNotPreparedException`).

## 검토한 대안
- CDC(Debezium): 지연이 작지만 커넥터·Kafka Connect 운영이 범위를 넘음(명세상 범위 외). 기각.
- 커밋 후 `@TransactionalEventListener(AFTER_COMMIT)` 로 send: 커밋과 send 사이에 죽으면 유실. 기각.
- Kafka 트랜잭션 프로듀서 + DB 트랜잭션 동기화: 두 자원의 원자성을 보장하지 못함(ADR-0004). 기각.

## 근거
`OutboxRelayTest`(헤더·SENT·5회 FAILED·7일 삭제), `OutboxFailedEventTest`(실패 중·FAILED 뒤 후속 보류, 재큐 후 순서대로 발행, 404·409·403), `OrderPlacementTest.orderPlacedWhileKafkaIsDownIsPublishedAfterItComesBack`
(브로커 정지 12초 동안 PENDING 유지, retry_count 0, 재개 후 SENT).
