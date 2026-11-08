# ADR-0005 Schema Registry 미사용, JSON 봉투 + version 필드

## 배경
이벤트 스키마가 바뀌면 생산자와 소비자가 어긋난다. 보통은 Avro/Protobuf + Schema Registry 로 호환성을 검사한다.
이 프로젝트는 생산자와 소비자가 **같은 앱·같은 배포 단위**(ADR-0001)이고 이벤트 클래스도 같은 소스(`event/*Event`)를 쓴다.

## 결정
- 페이로드는 JSON 문자열(`StringSerializer`), 모든 메시지는 같은 봉투
  `{eventId, eventType, version, occurredAt, aggregateId, traceId, payload}` (`event/EventEnvelope`).
- `version` 필드(현재 1)로 페이로드 구조 변경을 표시한다. 필드 추가는 하위 호환(소비 측은 모르는 필드를 무시), 의미 변경이나
  삭제는 version 을 올리고 소비자가 둘 다 읽을 수 있을 때까지 유지한다.
- 계약은 테스트로 고정한다: `EventEnvelopeContractTest` 가 모든 이벤트 타입의 봉투 필드와 페이로드에 개인정보가 없음을 검사한다.
- 깨진 메시지는 `MalformedEventException` → 재시도 없이 DLT(ADR-0004). 소비자는 죽지 않고 다음 레코드로 넘어간다.

## 결과
- 좋은 점: 인프라가 Kafka 하나로 끝나고, kafka-ui 에서 메시지를 그대로 읽을 수 있다(시연·디버깅에 유리).
- 나쁜 점: 호환성 검사가 빌드 타임이 아니라 테스트 타임이다. 팀·서비스가 나뉘면 이 결정은 다시 봐야 한다(그때 Registry 도입).
  JSON 은 바이너리보다 크다(이 규모에서는 무시).

## 검토한 대안
- Avro + Confluent Schema Registry: 호환성 강제가 확실하지만 컨테이너·직렬화기·코드 생성이 추가되고 명세상 범위 외. 기각.
- Spring Kafka `JsonSerializer` 타입 헤더(`__TypeId__`): 클래스명이 메시지에 박혀 리팩터링에 약함. 기각.
