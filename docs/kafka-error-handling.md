# Kafka 오류 처리 정리 (shop-mall 소스 기준)

> 이 문서는 shop-mall 예제에서 Kafka 처리 중 오류가 났을 때 **어디서, 어떻게** 처리되는지를 실제 소스 위주로 정리한다.
> 관련 ADR: [0002 Transactional Outbox](adr/0002-transactional-outbox.md), [0004 At-least-once + 멱등 컨슈머](adr/0004-at-least-once-idempotent-consumer.md)

---

## 0. 큰 그림 — 오류는 두 군데서 난다

```
[도메인 트랜잭션]                 [발행 측 = Producer]                    [소비 측 = Consumer]
OrderService ──record()──▶ outbox_events ──OutboxRelay──▶ Kafka topic ──@KafkaListener──▶ 핸들러
                           (PENDING)       │  실패?                      │  실패?
                                           ▼                              ▼
                              ① 브로커 장애 → PENDING 유지      ③ DefaultErrorHandler 재시도 (1s/2s/4s)
                              ② 그 외 실패 5회 → FAILED          ④ 그래도 실패 → <topic>.DLT
                                           │                              │
                                           ▼                              ▼
                              관리자 requeue (FAILED→PENDING)     DeadLetterListener → dead_letter_events
                                                                          │
                                                                          ▼
                                                                  관리자 retry → outbox 경유 재발행
```

| 구분 | 실패 유형 | 처리 방식 | 핵심 소스 |
|---|---|---|---|
| 발행 | Kafka 브로커 다운/타임아웃 | 횟수 안 세고 PENDING 유지 → 복구 후 자동 발행 | `OutboxRelay.handleFailure` |
| 발행 | 그 외 발행 실패 | 5회 누적 시 FAILED, 같은 주문 후속 이벤트 보류 | `OutboxEvent.recordFailure`, `lockPending` |
| 소비 | 일시적 오류 (DB 락 등) | 지수 백오프 3회 재시도 후 DLT | `KafkaConfig.kafkaErrorHandler` |
| 소비 | 영구적 오류 (파싱 실패, 비즈니스 규칙 위반) | 재시도 없이 즉시 DLT | `addNotRetryableExceptions` |
| 소비 | 같은 이벤트 중복 수신 | `processed_events` PK로 건너뜀 | `IdempotentEventProcessor` |
| 복구 | DLT에 쌓인 이벤트 | DB 저장 → 관리자 재처리 | `DeadLetterListener`, `DeadLetterRetryService` |

---

## 1. 발행 측(Producer) 오류 처리

### 1-1. 왜 Kafka에 바로 send 하지 않는가 — Transactional Outbox

`src/main/java/com/example/shop/event/EventOutbox.java`

```java
@Transactional(propagation = Propagation.MANDATORY)   // 반드시 호출자 트랜잭션 안에서
public void record(DomainEvent event) {
    EventEnvelope<DomainEvent> envelope = EventEnvelope.wrap(event, TraceIds.currentOrNew());
    outbox.save(new OutboxEvent(envelope.eventId(), event.type().topic(),
            String.valueOf(event.orderId()), toJson(envelope)));
}
```

- 주문 저장과 이벤트 저장이 **같은 DB 트랜잭션**이다. 그래서
  - Kafka가 죽어 있어도 주문은 정상 commit 된다 (Kafka 장애가 비즈니스를 롤백시키지 않음).
  - 주문이 롤백되면 이벤트도 함께 사라진다 ("주문은 없는데 이벤트만 나가는" 상황 방지).
- `MANDATORY` → 트랜잭션 밖에서 호출하면 예외. 실수로 이벤트만 저장되는 버그를 원천 차단.

### 1-2. Producer 설정 — 유실·중복 쓰기 방지

`src/main/resources/application.yml`

```yaml
spring.kafka.producer:
  acks: all                       # 모든 ISR 복제본이 받아야 성공
  properties:
    enable.idempotence: true      # 프로듀서 내부 재시도로 인한 중복 쓰기 방지
    max.block.ms: 10000           # 메타데이터 못 받으면 10초 후 포기
    request.timeout.ms: 5000
    delivery.timeout.ms: 10000    # 전송 전체를 10초 안에 포기
```

타임아웃을 짧게 잡은 이유: 장애 중 relay 스레드가 몇 분씩 묶이지 않고 빨리 "실패"로 판정해 다음 poll로 넘기기 위함.

### 1-3. OutboxRelay — 실패를 "브로커 장애"와 "그 외"로 나눈다

`src/main/java/com/example/shop/event/OutboxRelay.java`

```java
@Scheduled(fixedDelay = POLL_DELAY_MILLIS)   // 500ms마다 polling
public void publishPending() {
    transaction.executeWithoutResult(status -> publish(outbox.lockPending(BATCH_SIZE)));
}

private PublishResult publishOne(OutboxEvent event) {
    try {
        // 동기 대기: 브로커 ack를 받아야 SENT로 표시
        kafka.send(toRecord(event, headers)).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        event.markSent();
        return PublishResult.SENT;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return PublishResult.BROKER_UNAVAILABLE;
    } catch (Exception e) {
        return handleFailure(event, e);
    }
}

private PublishResult handleFailure(OutboxEvent event, Exception failure) {
    if (isBrokerUnavailable(failure)) {
        // ① 브로커 장애: retryCount 증가 없음 → PENDING 그대로 → 복구되면 자동 발행
        return PublishResult.BROKER_UNAVAILABLE;
    }
    // ② 그 외 실패: 횟수 누적
    event.recordFailure();
    return PublishResult.FAILED;
}

private static boolean isBrokerUnavailable(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {   // cause 체인 전체 검사
        if (cause instanceof TimeoutException || cause instanceof RetriableException) {
            return true;
        }
    }
    return false;
}
```

포인트:
- **브로커 장애는 "이벤트 탓"이 아니므로 횟수를 세지 않는다.** Kafka가 1시간 죽어 있어도 이벤트는 FAILED로 빠지지 않고, 살아나면 그대로 나간다.
- `RetriableException`은 Kafka 클라이언트가 "다시 하면 될 수도 있다"고 분류한 예외 계열(`NotLeaderOrFollowerException`, `NetworkException` 등).
- `SEND_TIMEOUT(15s)` > `delivery.timeout.ms(10s)` — 프로듀서 내부 시도가 완전히 끝난 뒤에 판정하도록.
- ack 유실 시(브로커는 저장했는데 응답이 안 옴) 다음 poll에서 **다시 발행 → 중복 가능** = at-least-once. 중복은 소비 측에서 거른다(2-3).

### 1-4. FAILED 처리와 순서 보장

`OutboxEvent.java`

```java
static final int MAX_ATTEMPTS = 5;

void recordFailure() {
    retryCount++;
    if (retryCount >= MAX_ATTEMPTS) {
        status = OutboxStatus.FAILED;     // 5회 실패 → 포기, 관리자 화면에 노출
    }
}
```

`OutboxRelay.publish` — 같은 배치 안에서 순서 지키기

```java
private void publish(List<OutboxEvent> events) {
    Set<String> failedOrders = new HashSet<>();
    for (OutboxEvent event : events) {
        if (failedOrders.contains(event.getMessageKey())) continue;   // 같은 주문 뒤 이벤트는 건너뜀
        PublishResult result = publishOne(event);
        if (result == PublishResult.BROKER_UNAVAILABLE) return;       // 브로커 장애면 배치 중단
        if (result == PublishResult.FAILED) failedOrders.add(event.getMessageKey());
    }
}
```

`OutboxEventRepository.lockPending` — 다음 poll 이후에도 순서 지키기

```sql
select o.* from outbox_events o
where o.status = 'PENDING'
  and not exists (select 1 from outbox_events f
                  where f.message_key = o.message_key and f.status = 'FAILED' and f.id < o.id)
order by o.id
limit :limit
for update of o skip locked      -- 여러 인스턴스가 같은 행을 동시에 발행하지 않음
```

- 앞선 이벤트(예: `order.created`)가 FAILED면 같은 주문의 뒤 이벤트(`order.cancelled`)는 **보류**된다 → 소비자가 "취소"를 "생성"보다 먼저 받는 일이 없다.
- Kafka의 순서 보장은 **같은 파티션 내**뿐이므로 key = orderId로 보내고, 발행 순서 자체도 이렇게 지켜야 의미가 있다.

### 1-5. FAILED 복구 — 관리자 requeue

`OutboxRecoveryService.java` / API: `POST /api/admin/outbox/{id}/requeue`

```java
@Transactional
public void requeue(long outboxEventId) {
    if (outbox.requeueFailed(outboxEventId) == 1) return;   // FAILED → PENDING, retryCount = 0
    if (!outbox.existsById(outboxEventId)) throw new ShopException(ErrorCode.OUTBOX_EVENT_NOT_FOUND);
    throw new ShopException(ErrorCode.OUTBOX_EVENT_NOT_FAILED);   // 이미 처리됨 → 409
}
```

```sql
update OutboxEvent e set e.status = PENDING, e.retryCount = 0
where e.id = :id and e.status = FAILED      -- 조건부 update라 더블클릭해도 한 번만 반영
```

---

## 2. 소비 측(Consumer) 오류 처리

### 2-1. 공통 에러 핸들러 — 재시도 + DLT

`src/main/java/com/example/shop/event/KafkaConfig.java`

```java
private static final int MAX_RETRIES = 3;                 // 최초 1회 + 재시도 3회 = 총 4회
private static final long FIRST_RETRY_INTERVAL_MILLIS = 1_000;
private static final double RETRY_MULTIPLIER = 2.0;       // 1s → 2s → 4s

@Bean
DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaTemplate) {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
    backOff.setInitialInterval(FIRST_RETRY_INTERVAL_MILLIS);
    backOff.setMultiplier(RETRY_MULTIPLIER);

    DefaultErrorHandler handler = new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(kafkaTemplate),   // 재시도 소진 시 <topic>.DLT 로 발행
            backOff);

    // 재시도해도 결과가 같은 예외 → 바로 DLT
    handler.addNotRetryableExceptions(
            MalformedEventException.class,           // JSON 파싱 불가 / 필수 필드 누락
            InvalidStateTransitionException.class,   // 상태 전이표가 금지한 전이
            ShopException.class);                    // 비즈니스 규칙 위반 (존재하지 않는 주문 등)
    return handler;
}
```

동작 흐름 (`@KafkaListener` 메서드가 예외를 던지면):

```
시도1 실패 ─1s─▶ 시도2 실패 ─2s─▶ 시도3 실패 ─4s─▶ 시도4 실패 ─▶ DeadLetterPublishingRecoverer
                                                                    └─▶ shop.order.created.DLT (같은 partition)
NotRetryable 예외 ───────────────────────────────────────────────────▶ 즉시 DLT
```

- Spring Boot는 `DefaultErrorHandler` 빈이 하나 있으면 **기본 listener container factory에 자동 적용**한다 → 각 리스너에 따로 설정할 필요 없음.
- 재시도는 `DefaultErrorHandler`가 **같은 offset으로 seek** 해서 다시 poll 하는 방식이다. 즉 실패한 record 뒤의 record들은 재시도 동안 대기한다 (파티션 순서 유지, 대신 지연 발생).
- `DeadLetterPublishingRecoverer`는 DLT로 보낼 때 원본 topic/partition/offset, 예외 클래스·메시지·스택트레이스를 **헤더**(`kafka_dlt-*`)에 넣어준다.
- **재시도 가능/불가 분류가 오류 처리 설계의 핵심**이다. 파싱 실패를 4번 재시도해봐야 7초 낭비일 뿐이다.

### 2-2. 오프셋 커밋 설정

`application.yml`

```yaml
spring.kafka:
  consumer:
    auto-offset-reset: earliest   # 새 group은 처음부터 읽음 → 그룹 합류 전 이벤트 누락 방지
  listener:
    ack-mode: record              # record 하나 처리 성공할 때마다 offset commit
```

- 핸들러가 정상 리턴 → offset commit. 예외 → commit 안 함 → 에러 핸들러가 재시도/DLT 처리 후 다음으로 진행.
- `record` 모드라 배치 중간 실패 시 이미 성공한 record가 다시 오는 범위가 최소화된다.

### 2-3. 멱등 소비 — 재시도/중복에 안전하게

재시도와 at-least-once 발행 때문에 **같은 이벤트가 여러 번 올 수 있다.** 그래서 모든 리스너는 이 프로세서를 거친다.

`src/main/java/com/example/shop/event/IdempotentEventProcessor.java`

```java
public <P> void process(String consumerGroup, ConsumerRecord<String, String> record,
                        Class<P> payloadType, Consumer<EventEnvelope<P>> handler) {
    EventEnvelope<P> event = parse(record, payloadType);          // 실패 시 MalformedEventException → 즉시 DLT
    transaction.executeWithoutResult(status -> {
        if (!markProcessed(consumerGroup, event.eventId())) {      // 이미 처리한 이벤트
            log.info("duplicate event skipped ...");
            return;                                                // 정상 리턴 → offset commit
        }
        failureSwitch.failIfArmed(consumerGroup, event.eventId()); // (시연용 장애 주입)
        handler.accept(event);                                     // 비즈니스 로직
    });
}

private boolean markProcessed(String consumerGroup, String eventId) {
    try {
        jdbc.update("insert into processed_events (consumer_group, event_id, processed_at) values (?, ?, ?)", ...);
        return true;
    } catch (DuplicateKeyException alreadyProcessed) {
        return false;
    }
}

private <P> EventEnvelope<P> parse(ConsumerRecord<String, String> record, Class<P> payloadType) {
    try {
        event = objectMapper.readValue(record.value(), envelopeType);
    } catch (JsonProcessingException | IllegalArgumentException e) {
        throw new MalformedEventException(record.topic(), e);     // 재시도 불가 예외로 변환
    }
    if (event == null || !event.hasRequiredFields()) throw new MalformedEventException(record.topic());
    return event;
}
```

핵심 아이디어:
- `processed_events(consumer_group, event_id)` insert와 비즈니스 처리가 **한 트랜잭션**.
  - 핸들러 성공 → 둘 다 commit → 다음에 같은 이벤트가 오면 PK 충돌로 skip.
  - 핸들러 실패 → 둘 다 rollback → 재시도 때 **처음부터 다시 처리**된다 (처리 기록만 남고 로직은 안 된 상태가 생기지 않음).
- "먼저 select 해서 확인" 대신 **"먼저 insert"** — 동시에 들어온 중복 2건이 둘 다 "미처리"로 판단하는 경쟁 조건을 PK 락으로 막는다.
- group 단위로 기록하므로 order 모듈은 처리했고 payment 모듈은 실패한 경우, 재발행 시 **실패한 group만 다시 처리**한다.
- 파싱 예외를 도메인 예외(`MalformedEventException`)로 **변환해 던지는 것**이 2-1의 재시도 분류와 연결되는 지점이다.

리스너 쪽은 단순하다 — `src/main/java/com/example/shop/order/OrderEventListener.java`

```java
@KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = ConsumerGroups.ORDER)
void onPaymentCompleted(ConsumerRecord<String, String> record) {
    processor.process(ConsumerGroups.ORDER, record, PaymentCompletedEvent.class,
            event -> results.applyPaymentCompleted(...));
}
```

리스너에는 try/catch가 **없다.** 예외는 그냥 던지고, 재시도·DLT 판단은 `DefaultErrorHandler`에 일임한다. (리스너에서 예외를 삼키면 offset이 commit 돼서 이벤트가 조용히 유실된다 — 가장 흔한 실수.)

---

## 3. DLT(Dead Letter Topic) 관리

### 3-1. 토픽 생성 — 원본마다 DLT 한 쌍

`KafkaConfig.shopTopics`

```java
for (String topic : EventTopics.ALL) {
    topics.add(newTopic(topic));                              // shop.order.created
    topics.add(newTopic(topic + EventTopics.DLT_SUFFIX));     // shop.order.created.DLT
}
```

- `DeadLetterPublishingRecoverer`는 기본적으로 **원본과 같은 partition 번호**로 DLT에 보낸다 → DLT의 파티션 수가 원본 이상이어야 한다 (여기서는 둘 다 `PARTITIONS = 3`).

### 3-2. DLT 소비 → DB 저장

`src/main/java/com/example/shop/event/DeadLetterListener.java`

```java
@KafkaListener(groupId = ConsumerGroups.DEAD_LETTER, topics = {
        EventTopics.ORDER_CREATED_DLT, EventTopics.ORDER_CANCELLED_DLT, ...})
void store(ConsumerRecord<String, String> record) {
    String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);   // recoverer가 넣은 헤더
    if (originalTopic == null) {
        originalTopic = record.topic().substring(0, record.topic().length() - EventTopics.DLT_SUFFIX.length());
    }
    String error = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
    deadLetters.save(new DeadLetterEvent(originalTopic, String.valueOf(record.value()), error));
}
```

- DLT를 Kafka에만 두면 조회·재처리가 불편하므로 `dead_letter_events` 테이블로 옮겨 **관리자 화면에서 조회**한다.
- 별도 group(`shop-dead-letter`)이라 모듈 group의 장애와 독립적이다.

### 3-3. 재처리 — 반드시 outbox를 거친다

`src/main/java/com/example/shop/event/DeadLetterRetryService.java` / API: `POST /api/admin/dlt/{id}/retry`

```java
@Transactional
public void retry(long deadLetterId) {
    DeadLetterEvent deadLetter = deadLetters.findById(deadLetterId)
            .orElseThrow(() -> new ShopException(ErrorCode.DEAD_LETTER_NOT_FOUND));
    if (deadLetter.isRetried()) throw new ShopException(ErrorCode.DEAD_LETTER_ALREADY_RETRIED);  // 1회만
    RetryTarget target = retryTargetOf(deadLetter);          // 깨진 envelope는 재처리 불가
    outbox.republish(target.eventId(), deadLetter.getOriginalTopic(), target.messageKey(), deadLetter.getPayload());
    deadLetter.markRetried();
}
```

`EventOutbox.republish`

```java
// 원래 eventId 그대로 재발행 → 이미 처리한 group은 processed_events로 skip, 실패했던 group만 처리
outbox.findByEventId(eventId).ifPresentOrElse(OutboxEvent::requeue,
        () -> outbox.save(new OutboxEvent(eventId, topic, messageKey, envelopeJson)));
```

설계 포인트:
- `KafkaTemplate`으로 직접 보내지 않고 **outbox에 다시 넣는다** → 재처리도 발행 측 오류 처리(1장)의 보호를 그대로 받는다.
- **eventId를 바꾸지 않는다** → 멱등 처리(2-3)와 맞물려 "실패했던 consumer group만" 다시 처리.
- 재처리가 또 실패하면 새 dead letter로 다시 들어온다 (무한 루프 대신 사람이 판단).

---

## 4. 추적 — 오류 로그에서 흐름 따라가기

`src/main/java/com/example/shop/event/TraceRecordInterceptor.java`

```java
public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    Header traceId = record.headers().lastHeader(EventHeaders.TRACE_ID);
    MDC.put(MdcKeys.TRACE_ID, traceId == null ? TraceIds.newTraceId() : new String(traceId.value(), UTF_8));
    if (record.key() != null) MDC.put(MdcKeys.ORDER_ID, record.key().toString());
    return record;
}
```

- OutboxRelay가 헤더에 넣은 `traceId`를 consumer MDC로 복원 → **HTTP 요청 → 발행 → 소비 → 재시도 → DLT** 로그를 한 traceId로 grep 할 수 있다.

---

## 5. 직접 실습해보기 — 장애 주입 스위치

`ConsumerFailureSwitch.java` (local/test profile에서 `shop.demo.failure-switch-enabled=true`일 때만 동작)

```bash
# shop-payment group이 다음에 처리하는 이벤트 1건을 모든 시도에서 실패시킴
# (/api/admin/** 는 ADMIN 권한 필요 — 관리자로 로그인한 세션 쿠키가 있어야 한다. 관리자 이벤트 화면에서 해도 동일)
curl -X POST localhost:8080/api/admin/failure-switch \
  -H 'Content-Type: application/json' \
  -d '{"consumerGroup":"shop-payment","count":1}'
```

```java
void failIfArmed(String consumerGroup, String eventId) {
    ...
    int attempt = failedAttempts.merge(eventKey, 1, Integer::sum);
    if (attempt >= KafkaConfig.DELIVERY_ATTEMPTS) failedAttempts.remove(eventKey);  // 4회 실패 후 해제
    throw new ForcedConsumerFailure(consumerGroup, eventId, attempt);              // RuntimeException → 재시도 대상
}
```

관찰 순서:
1. 주문 생성 → 로그에서 `forced failure ... attempt=1`~`4`가 1s/2s/4s 간격으로 찍히는 것 확인 (재시도 백오프)
2. `dead letter stored: originalTopic=shop.order.created ...` 로그 확인 (DLT 이동)
3. 관리자 화면 또는 `POST /api/admin/dlt/{id}/retry` → outbox 경유 재발행
4. 로그에서 다른 group은 `duplicate event skipped`, `shop-payment`만 `event consumed` 확인 (멱등 + 선택적 재처리)

브로커 장애 실습: `docker compose stop kafka` → 주문 생성 (정상 성공) → `Kafka unavailable, event stays PENDING` 로그 → `docker compose start kafka` → 자동 발행 확인.

---

## 6. 요약 — 오류 처리 체크리스트

| # | 원칙 | 이 예제에서의 구현 |
|---|---|---|
| 1 | Kafka 장애가 비즈니스 트랜잭션을 깨지 않게 | Transactional Outbox (`EventOutbox.record`, `MANDATORY`) |
| 2 | 일시 장애와 영구 실패를 구분 | Producer: `RetriableException` 판별 / Consumer: `addNotRetryableExceptions` |
| 3 | 재시도는 간격을 늘려가며, 횟수 제한 | `ExponentialBackOffWithMaxRetries(3)` 1s/2s/4s, outbox 5회 |
| 4 | 끝내 실패한 건 버리지 말고 격리 | DLT + `dead_letter_events`, outbox `FAILED` |
| 5 | 중복 수신은 당연하다고 가정 | `processed_events` PK + 같은 트랜잭션 |
| 6 | 순서가 중요한 이벤트는 실패 시 뒤를 막는다 | key=orderId, `lockPending`의 FAILED 보류 조건 |
| 7 | 리스너에서 예외를 삼키지 않는다 | 리스너엔 try/catch 없음, 에러 핸들러에 일임 |
| 8 | 재처리는 사람이 판단, 원래 eventId 유지 | `DeadLetterRetryService.retry` → outbox republish |
| 9 | 장애를 추적할 수 있게 | traceId 헤더 + `TraceRecordInterceptor` MDC |
