# ADR-0001 모듈러 모놀리스, 모듈 간 상태 변경은 Kafka 이벤트로만

## 배경
포트폴리오의 목적은 "주문 → 결제 → 재고 → 알림" 사이의 **비동기 정합성**(초과 판매 0 · 중복 처리 0 · 유실 0)을 보여 주는 것이다.
서비스를 여러 개로 쪼개면 배포·네트워크·서비스 탐색·분산 추적 같은 운영 비용이 주제를 덮는다. 반대로 한 앱 안에서 서비스끼리
메서드를 직접 부르면 Kafka 를 쓰는 이유(장애 격리, 재시도, 순서)가 드러나지 않는다.

## 결정
- 앱은 하나(`com.example.shop`), 모듈은 패키지: `member · product · cart · order · stock · payment · notification · event · common · web`.
- **다른 모듈의 상태를 바꾸는 일은 Kafka 이벤트로만** 한다. 예: payment 는 `orders` 를 UPDATE 하지 않고 `shop.payment.completed` 를
  발행하며, order 모듈의 컨슈머(그룹 `shop-order`)가 PAID 로 바꾼다.
- 조회는 예외로 둔다(예: notification 이 회원 ID 를 order 모듈 조회 메서드로 읽음). 쓰기만 이벤트로 제한한다.
- 스키마도 같은 규칙: 외래 키는 한 모듈 안에서만 건다(`order_items → orders` 는 되고 `payments.order_id` 는 값만).
- 컨슈머 그룹은 모듈 단위(`shop-order` · `shop-payment` · `shop-stock` · `shop-notification`)라, 나중에 모듈을 떼어 내도 그룹과
  토픽이 그대로 서비스 경계가 된다.

## 결과
- 좋은 점: 한 번의 `docker compose up` + `java -jar` 로 시연된다. 테스트는 앱 하나 + Testcontainers 3종이면 끝난다.
  이벤트 경계가 이미 있으므로 서비스 분리 시 바뀌는 것은 배포 단위뿐이다.
- 나쁜 점: 같은 JVM·같은 DB 라 "직접 UPDATE 금지"는 컴파일러가 막아 주지 않는다. 코드 리뷰와 README 규칙으로 지킨다.
  모듈 하나의 장애(메모리 부족 등)가 앱 전체를 멈춘다 — 대신 Kafka 오프셋과 아웃박스 덕분에 재기동 후 이어서 처리된다(ADR-0004).

## 검토한 대안
- 마이크로서비스: 운영 비용이 주제를 덮음. 기각.
- 모놀리스 + 동기 호출(@Transactional 하나로 주문·결제·재고): 가장 단순하지만 Kafka·멱등·보상을 보여 줄 수 없음. 기각.
- 모듈러 모놀리스 + Spring 애플리케이션 이벤트: 프로세스가 죽으면 이벤트가 사라짐(아웃박스가 필요해지는 이유). 기각.

## 근거
`TraceAcrossModulesTest`(traceId 하나로 주문 → 결제 → 재고 → PAID → 알림), `PaymentFlowTest`.
