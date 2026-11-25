# Redis 단계별 학습 (redisstudy)

Redis를 처음 쓰는 사람이 **명령어 기초 → 동시성 → Spring → 쇼핑몰 실전 코드**까지 단계적으로 올라가도록 만든 예제다.
각 단계는 `main()` 하나로 실행되고, 쇼핑몰 애플리케이션과는 분리되어 있다(Spring 빈으로 등록되지 않음).
학습용 키는 모두 `study:` 로 시작하므로 쇼핑몰 키(`shop:`)와 섞이지 않는다.

## 준비

```bash
docker compose up -d redis
```

- 접속 정보는 프로젝트 루트의 `.env`(`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`)를 자동으로 읽는다.
- 실행: IDE에서 각 `StepN...java` 파일의 `main()` 실행 (작업 디렉터리 = 프로젝트 루트).

### 강력 추천: MONITOR로 명령 실시간 보기
터미널 하나를 열어 두고 아래를 실행한 뒤 각 단계를 돌리면, 코드가 Redis에 보내는 **실제 명령**이 한 줄씩 찍힌다.
```bash
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli MONITOR'
```
직접 명령을 쳐 보고 싶으면 `MONITOR` 대신 아무것도 안 붙이면 대화형 redis-cli가 열린다 (`GET study:step1:greeting` 등).

## 커리큘럼

| 단계 | 파일 | 배우는 것 | 쇼핑몰에서 대응되는 코드 |
|---|---|---|---|
| 1 | `step1/Step1StringAndTtl` | SET / GET / DEL, TTL(자동 만료) | 로그인 실패 카운터, 멱등성 키 |
| 2 | `step2/Step2AtomicCounter` | GET+SET은 동시성에 깨진다, INCR은 원자적 | `scripts/login-failure.lua` |
| 3 | `step3/Step3HashCart` | Hash로 장바구니 | `cart/CartService` |
| 4 | `step4/Step4SetNxLock` | SET NX: 중복 요청 방지, 분산 락, 남의 락 지우는 버그 | `common/IdempotencyKeys`, `common/RedisLock` |
| 5 | `step5/Step5LuaStock` | Lua로 "확인 + 차감" 원자화 → 초과 판매 방지 | `scripts/stock-reserve.lua` |
| 6 | `step6/Step6DataStructures` | List / Set / Sorted Set (최근 본 상품, 좋아요, 랭킹) | (쇼핑몰 미사용, 실무 빈출) |
| 7 | `step7/Step7SpringDataRedis` | StringRedisTemplate ↔ Redis 명령 대응표 | Redis 쓰는 모든 서비스 |
| 8 | `step8/Step8Cache` | `@Cacheable` / `@CacheEvict`, TTL, 캐시 불일치 | `product/ProductCatalog` |
| 9 | (실전 코드 읽기) | Redis는 DB 트랜잭션 밖이다 → 보상 처리 | `stock/StockReservationService`, `stock/AfterRollback` |

각 파일 상단 주석에 **실행되는 Redis 명령과 결과 예시**, 일부는 **해볼 것**이 있다.

---

## 단계별 요약

### STEP 1. String과 TTL
- Redis = 메모리 위의 거대한 `Map<key, value>`. 디스크 DB보다 수십~수백 배 빠르다.
- 키마다 TTL을 걸면 Redis가 알아서 지운다 → 캐시, 세션, 임시 잠금에 적합.
- `TTL` 결과: `-1` = 만료 없음, `-2` = 키 없음, 양수 = 남은 초.

### STEP 2. 원자성 — 가장 중요한 개념
```
스레드 A: GET → 5          스레드 B: GET → 5
스레드 A: SET 6            스레드 B: SET 6     ← +1이 하나 사라짐
```
Redis는 **명령 하나씩** 순서대로 실행한다(싱글 스레드). 그래서 명령 하나(INCR)는 안전하지만, 명령 두 개(GET → SET) 사이엔 다른 요청이 끼어든다.
실행 결과: GET+SET = 1000보다 훨씬 작은 값, INCR = 정확히 1000.

### STEP 3. Hash
| 방식 | 수량 하나 변경 |
|---|---|
| String에 JSON 통째로 | 전체 GET → 수정 → 전체 SET (STEP 2의 lost update 위험) |
| Hash | `HINCRBY cart 3 1` 한 번 (원자적) |

TTL은 필드가 아니라 **키 전체**에 걸린다.

### STEP 4. SET NX
- **멱등성 키**: 사용자가 주문 버튼을 두 번 눌러도 주문은 하나. 두 번째 요청은 첫 요청의 결과(orderId)를 그대로 받는다.
- **분산 락**: 서버가 여러 대일 때 "한 대만 실행"(예: 결제 시한 지난 주문 취소 배치).
- 함정: TTL이 작업보다 먼저 끝나면 남이 락을 잡는다. 이때 그냥 `DEL` 하면 **남의 락을 지운다** → 토큰을 비교하고 지우는 Lua 스크립트로 해제.

### STEP 5. Lua 스크립트
"재고 확인 → 차감"처럼 **여러 명령을 하나로 묶어야** 할 때 쓴다. 스크립트가 도는 동안 다른 명령은 끼어들지 못한다.
실행 결과: GET → DECRBY 방식은 재고 10개에 50건 판매(재고 -40), Lua는 정확히 10건.
주의: 스크립트가 오래 걸리면 그동안 Redis 전체가 멈춘다 → 짧게 유지.

### STEP 6. 자료구조 고르기
| 자료구조 | 특징 | 예 |
|---|---|---|
| String | 값 하나 | 카운터, 캐시, 락 |
| Hash | 키 안의 필드들 | 장바구니, 객체 |
| List | 순서, 앞뒤 삽입 | 최근 본 상품, 간단한 큐 |
| Set | 중복 없음 | 좋아요 누른 사람, 태그 |
| Sorted Set | 점수로 정렬 | 랭킹, 시간순 목록 |

### STEP 7. Spring Data Redis
쇼핑몰은 `StringRedisTemplate`을 주입받아 쓴다. `opsForValue()` = String, `opsForHash()` = Hash, `execute(RedisScript, ...)` = Lua.
파일 주석의 **명령 ↔ 메서드 대응표**를 보고 쇼핑몰 코드를 읽으면 된다.

### STEP 8. 캐시
```
getProduct(3) ─▶ Redis에 있나? ─ 있음 ─▶ 바로 반환 (수 ms)
                     └─ 없음 ─▶ DB 조회 (느림) ─▶ Redis 저장(TTL) ─▶ 반환
changePrice(3) ─▶ DB 수정 ─▶ @CacheEvict 로 Redis 삭제 ─▶ 다음 조회 때 새 값 캐시
```
- 캐시를 안 지우면 TTL이 끝날 때까지 **옛날 값**이 보인다 (해볼 것에서 직접 확인).
- 쇼핑몰은 Java 클래스명 없는 순수 JSON으로 저장한다 → 클래스가 바뀌어도 역직렬화 오류가 나지 않음 (`product/ProductCacheConfig`).

### STEP 9. 실전 코드 읽기 — Redis는 DB 트랜잭션에 묶이지 않는다
주문 생성은 DB 트랜잭션 안에서 Redis 재고를 차감한다. 그런데 **DB가 롤백돼도 Redis 변경은 롤백되지 않는다.**
쇼핑몰은 이렇게 푼다:
1. `stock/StockReservationService.reserve` — DB에 예약 행 INSERT + Redis `EVAL stock-reserve.lua`로 차감
2. `stock/AfterRollback.undo` — 트랜잭션이 **롤백되면** Redis에 `INCRBY`로 되돌리는 보상 작업을 등록 (`TransactionSynchronization.afterCompletion`)
3. `stock/AvailableStockSynchronizer` — 그래도 어긋나면 기준 데이터인 DB로부터 Redis 재고를 다시 계산 (기동 시, 관리자 버튼)
4. epoch 카운터(`shop:stock-sync:epoch`) — 재동기화와 보상이 겹쳐 두 번 더해지는 것을 막는다

각 메서드 위 주석에 실제 명령 예시가 있다. 이 단계는 앞의 트랜잭션 학습(`src/test/java/com/example/shop/tran/README.md`)과 같이 보면 좋다.

---

## 운영에서 알아둘 것
- **KEYS 금지, SCAN 사용**: `KEYS *`는 키가 많으면 Redis 전체를 멈춘다. 이 예제들도 정리할 때 `SCAN`을 쓴다 (`StudyRedis.deleteKeys`).
- **Redis는 기준 데이터가 아니다**: 메모리라 유실될 수 있다. 쇼핑몰은 항상 DB를 기준으로 두고 Redis는 빠른 1차 방어선으로 쓴다 (멱등성 키도 DB 유니크 키가 2차 방어선).
- **TTL을 꼭 걸자**: 만료 없는 키가 쌓이면 메모리가 찬다. 쇼핑몰 키별 TTL은 `common/RedisKeys.java` 상단 표 참고.

## 정리 / 초기화
```bash
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --scan --pattern "study:*" | xargs -r -n 100 env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli DEL'
```
