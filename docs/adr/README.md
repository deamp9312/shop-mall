# 설계 결정 기록 (ADR, REQ-NF-005 4)

| 번호 | 결정 | 상태 |
|------|------|------|
| [0001](0001-modular-monolith.md) | 모듈러 모놀리스 + 모듈 간 상태 변경은 Kafka 이벤트로만 | 채택 |
| [0002](0002-transactional-outbox.md) | 트랜잭셔널 아웃박스 + 폴링 발행기 | 채택 |
| [0003](0003-redis-lua-stock-reservation.md) | Redis Lua 로 재고 선점, DB 는 정본 | 채택 |
| [0004](0004-at-least-once-idempotent-consumer.md) | at-least-once + 멱등 소비 (exactly-once 미사용) | 채택 |
| [0005](0005-no-schema-registry.md) | Schema Registry 미사용, JSON 봉투 + version | 채택 |
| [0006](0006-order-row-lock-for-status-change.md) | 주문 상태 변경 경로에 주문 행 잠금 | 채택 (4단계) |

형식: 배경 → 결정 → 결과(장단점) → 검토한 대안 → 근거 테스트.
