# 시퀀스 다이어그램 (REQ-NF-005 3)

네 흐름 모두 "한 트랜잭션 = 도메인 변경 + 아웃박스 1행" 이고, Kafka 발행은 `OutboxRelay`(500ms 폴링)가 커밋 뒤에 한다.
컨슈머 쪽 상자는 모두 `IdempotentEventProcessor` 의 한 트랜잭션(`processed_events` INSERT + 업무 처리)이다.
같은 이벤트가 두 번 와도 `processed_events` 기본 키 충돌로 두 번째는 건너뛴다. 그림에서는 생략한다.

## 1. 주문 → 결제 성공 → PAID

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자(브라우저)
    participant W as web (REST)
    participant O as order
    participant R as Redis
    participant DB as MySQL
    participant RL as OutboxRelay
    participant K as Kafka
    participant P as payment
    participant S as stock
    participant N as notification

    U->>W: POST /api/orders (Idempotency-Key, 상품·수량·배송지, 금액 없음)
    W->>O: place()
    O->>R: SET shop:idem:order:{key} NX EX 86400
    O->>DB: 상품 가격 조회 (서버 재계산)
    rect rgb(235, 245, 255)
    note over O,DB: 주문 트랜잭션
    O->>DB: INSERT orders(PAYMENT_PENDING) · order_items · order_status_history
    O->>DB: INSERT stock_reservations(RESERVED)
    O->>R: EVALSHA stock-reserve.lua (전 상품 검사 후 전부 차감, 하나라도 부족하면 0건)
    O->>DB: INSERT outbox_events(order.created)
    end
    O-->>U: 200 {orderId, paymentUrl}
    RL->>DB: SELECT ... FOR UPDATE SKIP LOCKED (PENDING)
    RL->>K: shop.order.created (key=orderId, 헤더 traceId)
    RL->>DB: status = SENT
    K->>P: shop-payment
    P->>DB: INSERT payments(READY, 금액·만료 시각·traceId)
    U->>W: GET /api/orders/{id}/status (1초 폴링, 결제 준비 확인)
    U->>W: POST /mock-pg/approve {result: SUCCESS} (Idempotency-Key = orderId)
    W->>P: approve()
    P->>R: SET shop:idem:payment:{orderId} NX
    rect rgb(235, 245, 255)
    note over P,DB: 승인 트랜잭션
    P->>DB: UPDATE payments SET status='APPROVED' WHERE id=? AND status='READY'
    P->>DB: INSERT outbox_events(payment.completed)
    end
    RL->>K: shop.payment.completed
    par shop-order
        K->>O: SELECT orders FOR UPDATE → PAID + 이력 1행
    and shop-stock
        K->>S: RESERVED→CONFIRMED 조건부 UPDATE → products.stock 차감 (Redis 변화 없음)
    and shop-notification
        K->>N: notification_logs 1행
    end
    U->>W: 상태 폴링 → PAID → 주문 상세로 이동
```

## 2. 결제 실패 → 재고 복원

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant P as payment
    participant DB as MySQL
    participant K as Kafka
    participant O as order
    participant S as stock
    participant R as Redis
    participant N as notification

    note over U,N: 주문 · READY 결제 생성까지는 1번과 같다
    U->>P: POST /mock-pg/approve {result: FAIL}
    rect rgb(235, 245, 255)
    P->>DB: UPDATE payments SET status='FAILED' WHERE id=? AND status='READY'
    P->>DB: INSERT outbox_events(payment.failed)
    end
    DB-->>K: OutboxRelay → shop.payment.failed
    par shop-order
        K->>O: PAYMENT_PENDING → PAYMENT_FAILED + 이력 1행
    and shop-stock
        K->>S: RESERVED→RELEASED 조건부 UPDATE (1행일 때만)
        S->>R: INCRBY shop:stock:{productId} (롤백되면 DECRBY)
    and shop-notification
        K->>N: notification_logs 1행
    end
```

## 3. 결제 시간 초과 자동 취소

```mermaid
sequenceDiagram
    autonumber
    participant J as PaymentTimeoutJob (1분마다)
    participant R as Redis
    participant DB as MySQL
    participant K as Kafka
    participant S as stock
    participant P as payment

    J->>R: SET shop:lock:order-timeout NX PX 50000
    alt 다른 인스턴스가 락 보유
        J-->>J: 이번 회차 건너뜀
    else 락 획득
        J->>DB: 만료된 PAYMENT_PENDING 주문 id 조회
        loop 주문마다 (각자 traceId)
            rect rgb(235, 245, 255)
            J->>DB: SELECT orders FOR UPDATE → 아직 PAYMENT_PENDING 인지 확인
            J->>DB: CANCELLED(PAYMENT_TIMEOUT) + 이력 1행 + outbox(order.cancelled)
            end
        end
        J->>R: 락 해제 (소유자 확인 Lua)
    end
    DB-->>K: OutboxRelay → shop.order.cancelled
    par shop-stock
        K->>S: RESERVED→RELEASED → INCRBY shop:stock:{productId}
    and shop-payment
        K->>P: READY → CANCELLED
    end
    note over K,P: 취소 뒤 늦게 온 payment.completed 는 order 가 payment.cancel-requested 로 되돌린다 (4번 하단)
```

## 4. 사용자 취소 (결제 전 · 결제 후)

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant O as order
    participant DB as MySQL
    participant K as Kafka
    participant S as stock
    participant R as Redis
    participant P as payment
    participant N as notification

    U->>O: POST /api/orders/{id}/cancel
    rect rgb(235, 245, 255)
    O->>DB: SELECT orders FOR UPDATE (본인 주문, PAYMENT_PENDING 또는 PAID 만)
    O->>DB: CANCELLED(USER_CANCEL) + 이력 1행 + outbox(order.cancelled)
    end
    O-->>U: 200 {status: CANCELLED} (그 외 상태는 409 ORDER_NOT_CANCELLABLE)
    DB-->>K: OutboxRelay → shop.order.cancelled
    par shop-stock
        alt 결제 전 (RESERVED)
            K->>S: RESERVED→RELEASED → INCRBY Redis
        else 결제 후 (CONFIRMED)
            K->>S: CONFIRMED→RELEASED → products.stock 복원 + INCRBY Redis
        end
    and shop-payment
        alt READY
            K->>P: READY → CANCELLED
        else APPROVED
            K->>P: 모의 환불 → CANCELLED + outbox(payment.cancelled)
            P-->>N: shop.payment.cancelled → 알림 로그
        end
    end

    note over O,P: 늦은 결제 성공 — 이미 CANCELLED 인 주문에 payment.completed 가 오면
    K->>O: shop-order: 무시하지 않고 outbox(payment.cancel-requested)
    O-->>P: shop.payment.cancel-requested → 모의 환불 → CANCELLED + payment.cancelled
```
