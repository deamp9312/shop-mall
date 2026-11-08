# ADR-0004 at-least-once 전달 + 멱등 소비 (exactly-once 미사용)

## 배경
Kafka 는 기본이 at-least-once 다. 아웃박스 발행기는 ack 를 잃으면 같은 이벤트를 다시 보내고(ADR-0002), 컨슈머는
처리 후 오프셋 커밋 전에 죽으면 재기동 후 같은 레코드를 다시 받는다. 결제 실패 이벤트를 두 번 처리하면 재고가 두 번 복원된다.

## 결정
- 모든 컨슈머는 `IdempotentEventProcessor` 를 거친다: **`processed_events(consumer_group, event_id)` INSERT 와 업무 처리를
  한 DB 트랜잭션**으로 묶는다. INSERT 를 먼저 하므로 동시에 온 중복은 기본 키에서 기다렸다가 실패하고 건너뛴다.
  업무 처리가 실패하면 INSERT 도 롤백되어 재시도 때 다시 처리된다.
- 업무 처리 자체도 조건부 UPDATE(`where status = ?`)로 두 번째 겹을 둔다(재고 예약, 결제 상태).
- `AckMode.RECORD`: 레코드마다 DB 커밋 뒤 오프셋을 커밋한다.
- `DefaultErrorHandler` 1s·2s·4s 3회 재시도(총 4회 시도) → `DeadLetterPublishingRecoverer` 가 `<topic>.DLT` 같은 파티션으로.
  역직렬화 실패(`MalformedEventException`)·전이표 위반(`InvalidStateTransitionException`)·업무 규칙 오류(`ShopException`)는
  다시 해도 같으므로 재시도 없이 DLT. DLT 는 `shop-dead-letter` 그룹이 `dead_letter_events` 에 저장하고 관리자가 같은 eventId 로 재처리한다.
- 생산자 `acks=all`, `enable.idempotence=true` 로 생산자 재시도 중복만 막는다.
- **exactly-once(트랜잭션 프로듀서)는 쓰지 않는다**: 업무 결과는 Kafka 가 아니라 MySQL·Redis 에 남는다. Kafka 트랜잭션은
  "읽고-Kafka에-쓰기"만 원자적으로 만들 뿐 DB 커밋과 묶이지 않으므로 "처리 1회"를 보장하지 못한다.
- Redis 보조 키 `shop:event:done:{group}:{eventId}` 는 계약에만 있고 쓰지 않는다 — 정본인 DB 확인이 어차피 필요하고,
  Redis 확인을 앞에 두면 한 번 더 왕복할 뿐 정합성은 늘지 않는다.

## 결과
- 좋은 점: 중복 처리 0. 앱이 처리 도중 강제 종료돼도 재기동 후 결과가 정확히 1회(REQ-NF-002 검증 3).
- 나쁜 점: `processed_events` 가 계속 쌓인다(정리 정책 없음 — 포트폴리오 범위). 컨슈머가 DB 밖에 남기는 부수 효과
  (Redis INCRBY)는 트랜잭션에 묶이지 않으므로 롤백 시 보상(`AfterRollback`)과 재동기화로 맞춘다(ADR-0003).

## 근거
`EventConsumptionTest`(같은 eventId 2회 → 1회, 강제 실패 → 4회 시도 후 DLT 1건·다음 레코드 계속, 형식 오류 → DLT),
`ConsumerCrashRecoveryTest`(재고 확정 도중 SIGKILL → 재기동 후 DB 재고 1회 차감, processed_events 1행, PAID 이력 1행),
`AdminEventTest`(DLT 재처리 시 이미 처리한 그룹은 건너뜀).
