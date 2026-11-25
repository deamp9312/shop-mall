# Kafka 단계별 학습 (kafkastudy)

Kafka를 처음 쓰는 사람이 **기초 → 쇼핑몰 실전 코드**까지 단계적으로 올라가도록 만든 예제다.
각 단계는 `main()` 하나로 실행되고, 쇼핑몰 애플리케이션과는 완전히 분리되어 있다(Spring 빈으로 등록되지 않음).

## 준비

```bash
docker compose up -d kafka          # Kafka 브로커만 띄우면 된다 (localhost:9092)
docker compose up -d kafka-ui       # (선택) http://localhost:8089 에서 topic / 메시지 / consumer group 을 눈으로 확인
```

실행: IDE에서 각 `StepN...java` 파일의 `main()`을 실행한다.
topic은 각 단계가 스스로 만든다 (`study.step1` ~ `study.step8`). docker-compose의 Kafka는 topic 자동 생성이 꺼져 있다.

## 커리큘럼

| 단계 | 파일 | 배우는 것 | 쇼핑몰에서 대응되는 코드 |
|---|---|---|---|
| 1 | `step1/Step1Producer`, `Step1Consumer` | topic, 보내기/받기, offset, consumer group 기본 | — |
| 2 | `step2/Step2KeyAndPartition` | key → partition, 순서 보장 범위 | key = orderId (`event/OutboxRelay.toRecord`) |
| 3 | `step3/Step3ConsumerGroup` | 같은 group은 나눠 받기, 다른 group은 모두 받기 | 모듈별 group (`event/ConsumerGroups`) |
| 4 | `step4/Step4OffsetCommit` | 수동 커밋, 커밋 전 장애 → 중복 (at-least-once) | `listener.ack-mode: record` (application.yml) |
| 5 | `step5/Step5ProducerReliability` | acks / idempotence, 콜백, 재시도 가능한 오류 구분 | `event/OutboxRelay.handleFailure` |
| 6 | `step6/Step6SpringKafka` | KafkaTemplate, `@KafkaListener` (Boot가 숨긴 설정 직접 보기) | `order/OrderEventListener` |
| 7 | `step7/Step7ErrorHandlingDlt` | DefaultErrorHandler 재시도, DLT, notRetryable | `event/KafkaConfig`, `event/DeadLetterListener` |
| 8 | `step8/Step8IdempotentConsumer` | eventId로 중복 제거 | `event/IdempotentEventProcessor` |
| 9 | (실전 코드 읽기) | Transactional Outbox | `event/EventOutbox` → `event/OutboxRelay` |

각 파일 상단 주석에 **[IN] / [OUT] 예시**와 **해볼 것**이 있다. 먼저 주석의 예상 출력을 읽고 → 실행해서 비교하고 → "해볼 것"으로 코드를 바꿔 보는 순서를 권한다.

---

## 단계별 요약

### STEP 1. 보내고 받기
```
Producer ──send("hello-0")──▶ [ study.step1 | 0 | 1 | 2 | ... ] ──poll()──▶ Consumer (group=study-step1)
                                  offset →                                    "어디까지 읽었나"를 group 이름으로 저장
```
- Kafka는 **읽어도 지우지 않는 로그**다. 메시지는 보관 기간 동안 남고, consumer는 자기 위치(offset)만 기억한다.
- Consumer를 다시 실행하면 이미 읽은 건 안 나온다. group 이름을 바꾸면 처음부터 다시 나온다.

### STEP 2. key와 partition
- topic은 partition 여러 개로 나뉘고, `hash(key) % partition 수`로 들어갈 partition이 정해진다.
- **순서는 같은 partition 안에서만 보장된다.** 그래서 "한 주문의 이벤트 순서"가 중요하면 key를 orderId로 준다.

### STEP 3. Consumer Group
```
                     ┌─ consumer A (group=work) ◀─ partition 0, 1
study.step3 (3개) ───┤─ consumer B (group=work) ◀─ partition 2         ← 같은 group: 나눠 받음 (부하 분산)
                     └─ consumer C (group=audit) ◀─ partition 0, 1, 2   ← 다른 group: 전부 받음 (각자 용도)
```
- 쇼핑몰: `shop.payment.completed` 하나를 order / stock / notification 모듈이 **각자 다른 group으로** 모두 받는다.
- partition보다 consumer가 많으면 남는 consumer는 논다 → partition 수가 병렬 처리의 상한.

### STEP 4. offset 커밋과 at-least-once
| 커밋 시점 | 장애 시 결과 | 이름 |
|---|---|---|
| 처리 **후** 커밋 | 같은 메시지 다시 처리 (중복) | at-least-once ← 쇼핑몰 선택 |
| 처리 **전** 커밋 | 메시지 한 번도 처리 안 됨 (유실) | at-most-once |

유실보다 중복이 낫다. 중복은 STEP 8로 막는다.

### STEP 5. Producer 신뢰성
- `acks=all` + `enable.idempotence=true`: 안전한 기본값 (쇼핑몰 application.yml과 동일).
- `send()`는 비동기 → **결과를 확인하지 않으면 실패를 모른다.** 콜백이나 `get()`으로 확인.
- 실패 원인이 `RetriableException`(브로커 장애, 타임아웃)이면 나중에 다시 보내면 된다.

### STEP 6. Spring Kafka
| 직접 (STEP 1~5) | Spring |
|---|---|
| `new KafkaProducer<>(props)` + `send()` | `KafkaTemplate.send(topic, key, value)` |
| `while(true) { poll(); ... commit(); }` | `@KafkaListener` 메서드 하나 |
| 설정 Properties | Spring Boot: application.yml `spring.kafka.*` |

### STEP 7. 오류 처리
```
listener 예외 ─▶ 재시도(1s) ─▶ 재시도(1s) ─▶ 포기 ─▶ study.step7.DLT 로 이동 ─▶ 다음 메시지 계속 처리
notRetryable 예외 ───────────────────────────────▶ 바로 DLT
```
- listener에서 예외를 **삼키지 말 것**. 삼키면 성공으로 보고 커밋돼서 메시지가 조용히 사라진다.
- 더 자세한 쇼핑몰 오류 처리: `docs/kafka-error-handling.md`

### STEP 8. 멱등 Consumer
- 메시지에 고유 eventId를 붙이고, 처리한 eventId를 기록해서 두 번째부터는 건너뛴다.
- 실무에서는 메모리가 아니라 **DB에, 비즈니스 처리와 같은 트랜잭션으로** 기록한다 (`event/IdempotentEventProcessor`).

### STEP 9. 실전 코드 읽기 — Transactional Outbox
"DB 저장"과 "Kafka 전송"은 하나의 트랜잭션으로 묶을 수 없다. 쇼핑몰은 이렇게 푼다:
1. `order/OrderService.create` — 주문 저장과 **같은 트랜잭션**으로 `outbox_events`에 이벤트 행 저장 (`event/EventOutbox.record`)
2. `event/OutboxRelay.publishOne` — 0.5초마다 PENDING 행을 읽어 Kafka로 전송, 성공하면 SENT
3. `payment/PaymentEventListener.onOrderCreated` — 받아서 `event/IdempotentEventProcessor`로 중복 확인 후 처리

각 메서드 위 주석에 실제 데이터 예시(orderId=7)가 있으니 1 → 2 → 3 순서로 따라가 보면 된다.

---

## 정리 / 초기화
학습용 topic을 지우고 처음부터 다시 하고 싶다면:
```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list | grep '^study\.' \
  | xargs -I{} docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --delete --topic {}
```
