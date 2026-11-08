# ADR-0003 Redis Lua 로 재고 선점, DB 는 정본

## 배경
한정 수량 상품에 주문이 몰리면 "재고 확인 → 차감" 사이에 다른 주문이 끼어 초과 판매가 난다. DB 비관적 락
(`SELECT ... FOR UPDATE`)은 정확하지만 모든 주문이 상품 행 하나에 줄을 서므로 처리량이 그 행의 잠금 시간에 묶이고,
여러 상품을 담은 주문은 잠금 순서까지 신경 써야 한다. `synchronized` 는 인스턴스가 둘이면 의미가 없다.

## 결정
- **정본은 DB `products.stock`**, Redis `shop:stock:{productId}` 는 "지금 팔 수 있는 수량"(가용 재고) 카운터다.
- 주문 시 `stock-reserve.lua` **한 번**으로 주문의 모든 상품을 검사하고, 하나라도 부족하면 아무것도 차감하지 않는다(전부 아니면 전무).
  카운터가 없으면 0(품절)으로 본다. Redis 는 스크립트를 원자적으로 실행하므로 검사와 차감 사이에 끼어들 틈이 없다.
- 같은 주문 트랜잭션에서 `stock_reservations`(RESERVED) 행을 Redis 차감 **전에** INSERT 하고, 트랜잭션이 롤백되면
  `AfterRollback` 으로 되돌린다. 단 보상은 롤백으로 DB 잠금이 풀린 **뒤**에 돌기 때문에, 그 사이 재동기화가 이미 DB 기준 값을
  썼다면 보상을 건너뛴다(리뷰 Medium-2, 아래 "보상과 재동기화").
- 확정·복원은 `stock_reservations.status` 조건부 UPDATE(`where status = ?`)가 1행일 때만 재고를 움직인다:
  payment.completed → CONFIRMED + DB 차감(Redis 그대로), payment.failed / order.cancelled → RELEASED + Redis INCRBY
  (결제 후 취소면 DB 도 복원). 같은 이벤트가 두 번 와도 1회만 반영된다.
- **재동기화 = DB 재고 − RESERVED 수량** (기동 시 + 관리자 버튼). 덮어쓰기이므로 `shop:lock:stock-resync` 를 잡고,
  2단계에서 발견한 경합(재동기화가 커밋 안 된 선점을 못 보고 덮어씀)은 **DB 잠금 읽기 순서**로 막는다(4단계 결정):
  `stock_reservations` → `products` 순서로 `FOR SHARE` 로 읽고 커밋 전에 Redis 를 쓴다. 선점 행이 Redis 차감보다 먼저
  INSERT 되므로, 커밋 안 된 주문이 있으면 재동기화가 기다리고 그 뒤 주문은 재동기화를 기다린다.
  관리자 재고 조정도 같은 순서(`FOR SHARE` → 상품 `FOR UPDATE`)로 하고 Redis 는 **차이만큼 INCRBY** 한다(덮어쓰지 않음).
  재고 차감 자체에는 DB 잠금을 쓰지 않는다 — 잠금 읽기는 드문 관리 작업에만 있다.
- **보상과 재동기화 (리뷰 2026-11-06 Medium-1·2)**: 재동기화는 DB 를 읽은 뒤 카운터를 쓰기 전에 `shop:stock-sync:epoch` 를
  INCR 한다. 선점·복원·관리자 조정은 재동기화가 기다려야 하는 행을 잡은 트랜잭션 안에서 epoch 를 읽어 두고, 롤백 보상은
  `stock-add.lua` 가 epoch 가 그대로일 때만 적용한다. epoch 가 바뀌었다는 것은 그 재동기화가 롤백 뒤의 DB 를 읽었다는 뜻이다.
  복원·보상은 여러 상품을 Lua 한 번에, 모든 카운터를 검사한 뒤 INCRBY 하므로 일부만 반영되는 일이 없다.
  검토한 대안: Redis 차감을 커밋 직전(`beforeCommit`)으로 옮기기 — 창은 좁아지지만 커밋 실패 시 같은 문제가 남고 품절 판정이
  커밋 시점으로 밀려 오류 처리 경로가 바뀐다. 보상을 재동기화 락 안에서 실행 — 락 순서만 바꿀 뿐 "재동기화 후 보상" 순서를
  막지 못한다. 둘 다 기각.

## 결과
- 좋은 점: 주문 경로의 재고 경합은 Redis 단일 스레드 안에서 끝난다. 재고 100 / 동시 200 주문에서 성공 정확히 100,
  Redis 0, 선점 합계 100 (10회 연속, README "동시성 검증").
- 나쁜 점: 상태가 두 곳(Redis 가용, DB 정본)이라 맞추는 규칙이 필요하다. Redis 데이터를 잃으면 재동기화 전까지 품절로 보인다
  (안전한 쪽으로 틀린다). 컨슈머가 Redis INCRBY 후 커밋 전에 **프로세스째 죽으면** DB 는 롤백되고 Redis 만 늘어난 채 남는다
  (`AfterRollback` 은 같은 프로세스 안의 롤백만 처리). 기동 시 재동기화가 DB 기준으로 다시 쓰면 바로잡히지만, **보장은 아니다**:
  기동 시 재동기화는 다른 인스턴스가 `shop:lock:stock-resync` 를 잡고 있으면 건너뛰고(관리자 [재동기화] 버튼으로 다시 해야 함),
  바로잡히기 전에 늘어난 수량만큼 초과 선점이 나면 그 주문의 확정(payment.completed → DB 차감)이 `ck_products_stock`(stock >= 0)
  CHECK 위반으로 실패해 재시도 후 DLT 로 간다. 이 경로에 자동 보정은 없다(README "알려진 한계" Low-3).
- 커밋 자체가 실패하면(커밋 전 Redis 변경 뒤 연결 끊김) 보상이 잠금 해제 뒤에 돈다. 재동기화와의 겹침은 epoch 로 막지만,
  그 사이 다른 주문이 관리자 조정의 증가분을 볼 수 있다.
- 부하 테스트(k6)에서는 초과 판매 0 을 HTTP 응답 수와 Redis 가용 재고 두 쪽에서 확인한다.

## 검토한 대안
- DB 비관적 락: 정확하지만 핫 상품 행에 직렬화. 명세가 대체를 금지(REQ-F-007). 처리량 비교 실험은 선택 항목이라 하지 않았다.
- DB 조건부 UPDATE(`stock = stock - ? where stock >= ?`): 락은 짧지만 여전히 행 잠금 직렬화, 다상품 전부-아니면-전무는 트랜잭션 롤백에 의존.
- Redis `DECR` 후 음수면 `INCR` 로 되돌리기: 순간적으로 음수가 보이고, 다상품 주문의 부분 차감을 되돌리는 코드가 복잡해짐. 기각.

## 근거
`StockConcurrencyTest`(REQ-NF-001 1·2), `StockReservationFlowTest`(전부 아니면 전무, 롤백 복원, 늦은 확정 무시),
`AvailableStockSynchronizerTest`·`StockAdjustmentRaceTest`(재동기화·조정 경합 재현), `OrderPlacementTest`(30 동시 / 재고 10),
`StockCompensationTest`(다상품 복원 부분 실패, 롤백 → 재동기화 → 보상 순서 강제: 주문·복원·조정).
