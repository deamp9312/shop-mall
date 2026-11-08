# ADR-0006 주문 상태 변경 경로에 주문 행 잠금 (4단계)

## 배경
주문 상태를 바꾸는 경로는 셋이다: 결제 결과 컨슈머(PAID / PAYMENT_FAILED), 사용자 취소, 결제 시간 초과 잡.
각 변경은 `order_status_history` INSERT(외래 키 → `orders` 행에 공유 잠금)와 `orders` UPDATE(배타 잠금)를 한다.
4단계 테스트에서 같은 주문에 두 변경이 동시에 들어오면 서로의 공유 잠금을 기다리며 **교착**(deadlock)되는 것을 재현했다.
`orders.version` 낙관적 락은 교착 뒤에야 판정하므로 막지 못한다.

## 결정
- 상태를 바꾸는 코드는 항상 `OrderRepository.findForStatusChange`(`SELECT ... FOR UPDATE`)로 주문 행을 먼저 잡고, 잡은 뒤에
  현재 상태를 확인해 전이한다. 두 번째 변경은 첫 번째 커밋을 기다렸다가 바뀐 상태를 보고 판단한다
  (예: 시간 초과 잡이 이미 CANCELLED 로 바꿨으면 늦은 payment.completed 는 payment.cancel-requested 를 발행).
- 이 잠금은 **같은 주문 행 하나**에만 걸리고 재고 차감에는 쓰지 않는다(재고는 ADR-0003 의 Redis Lua).
- 시간 초과 잡은 Redis 락 `shop:lock:order-timeout`(SET NX PX 50초)이 첫 겹, 주문 행 잠금 + 상태 확인이 두 번째 겹이다.
  락이 만료돼 두 인스턴스가 겹쳐 돌아도 order.cancelled 는 주문당 1건이다.

## 결과
- 좋은 점: 교착 0, 상태 확인과 변경 사이의 경합 0. 상태 전이는 여전히 엔티티 메서드로만 한다.
- 나쁜 점: 새 상태 변경 코드를 추가할 때 `findForStatusChange` 를 빠뜨리면 교착이 돌아온다(README 와 리포지토리 주석에 규칙 명시).

## 근거
`PaymentTimeoutJobTest`(두 인스턴스 동시 실행 → 주문당 취소 1건, 락 없이도 행 잠금으로 1건), `PaymentFlowTest`(취소 후 늦은 결제 성공 → 환불).
