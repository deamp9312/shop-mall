# @Transactional 동작 학습 (tran)

`@Transactional` 옵션에 따라 **언제 커밋되고 언제 롤백되는지**를 테스트로 확인하는 예제다.
Docker·DB 없이 바로 돈다.

```bash
mvn test -Dtest=TransactionalBehaviorTest
```

실행하면 테스트마다 콘솔에 이런 로그가 찍힌다. **이 로그를 읽는 게 학습의 핵심이다.**

```
[REQUIRES_NEW_안쪽_실패는_안쪽만_롤백된다()] [BEGIN tx1, INSERT outer (in tx1), SUSPEND tx1, BEGIN tx2, INSERT inner (in tx2), ROLLBACK tx2, RESUME tx1, COMMIT tx1] => DB: [outer]
```

## 파일 구성

| 파일 | 역할 |
|---|---|
| `FakeTransactionManager.java` | 가짜 DB + 트랜잭션 매니저. BEGIN/COMMIT/ROLLBACK을 로그로 남기고, 커밋된 행만 `committedRows()`에 남긴다 |
| `InnerService.java` | 안쪽에서 호출되는 서비스. propagation 별로 메서드 하나씩 |
| `OuterService.java` | 바깥 트랜잭션 역할. 시나리오별 메서드 |
| `TransactionalBehaviorTest.java` | 시나리오 11개. 기대 로그를 그대로 assert 한다 |

> 왜 가짜 매니저인가? "참여할지, 새로 열지, 롤백할지"는 모두 Spring의 `AbstractPlatformTransactionManager`가 판단한다.
> 실제 프로젝트의 `JpaTransactionManager`(MySQL)도 이 클래스를 상속하므로 **규칙은 똑같고**, 가짜 매니저는 DB에 명령을 보내는 마지막 단계만 흉내 낸다.

---

## 0. @Transactional은 어떻게 동작하나 — 프록시

```
테스트 코드 ──▶ [OuterService 프록시] ──▶ 진짜 OuterService.save()
                   │ 1) 트랜잭션 시작 (BEGIN)
                   │ 2) 진짜 메서드 호출
                   │ 3) 정상 리턴 → COMMIT / 예외 → 규칙에 따라 ROLLBACK
```

Spring은 `@Transactional`이 붙은 빈을 **프록시 객체로 감싸서** 등록한다. 트랜잭션은 이 **프록시를 거칠 때만** 적용된다.
이 한 줄이 아래 "자주 하는 실수"의 원인이다.

---

## 1. 예외 종류에 따른 커밋/롤백

| 상황 | 결과 | 테스트 |
|---|---|---|
| 예외 없음 | COMMIT | `예외가_없으면_커밋된다` |
| `RuntimeException`, `Error` | ROLLBACK | `런타임_예외면_롤백된다` |
| 체크 예외 (`Exception`, `IOException` …) | ⚠️ **COMMIT** | `체크_예외는_기본적으로_커밋된다` |
| 체크 예외 + `rollbackFor = Exception.class` | ROLLBACK | `rollbackFor를_주면_체크_예외도_롤백된다` |

```java
@Transactional
public void saveThenThrowChecked(String row) throws Exception {
    db.insert(row);
    throw new Exception("체크 예외");   // 예외가 나도 커밋된다!
}

@Transactional(rollbackFor = Exception.class)   // 체크 예외도 롤백하고 싶으면 명시
public void saveThenThrowCheckedWithRollbackFor(String row) throws Exception { ... }
```

체크 예외를 "예상 가능한 비즈니스 결과"로 보는 EJB 시절 관례 때문이다. 실무에선 보통 런타임 예외를 쓰거나 `rollbackFor`를 붙인다.
(반대로 `noRollbackFor = XxxException.class`로 특정 런타임 예외에서 커밋하게 할 수도 있다.)

---

## 2. 전파(propagation) — 트랜잭션 안에서 또 트랜잭션 메서드를 부르면?

| 옵션 | 바깥 트랜잭션이 있을 때 | 없을 때 |
|---|---|---|
| `REQUIRED` (기본값) | **참여** (같은 트랜잭션) | 새로 시작 |
| `REQUIRES_NEW` | 바깥을 **보류**하고 새로 시작 | 새로 시작 |
| `NOT_SUPPORTED` | 바깥을 보류하고 **트랜잭션 없이** 실행 | 트랜잭션 없이 실행 |
| `MANDATORY` | 참여 | **예외** (`IllegalTransactionStateException`) |
| `SUPPORTS` | 참여 | 트랜잭션 없이 실행 |
| `NEVER` | **예외** | 트랜잭션 없이 실행 |
| `NESTED` | savepoint 생성 (JDBC 전용, JPA 미지원) | 새로 시작 |

(`SUPPORTS`, `NEVER`, `NESTED`는 표로만 정리하고 테스트는 생략했다.)

### 2-1. REQUIRED — 안쪽 실패를 catch해도 전체가 롤백된다 ⚠️

가장 많이 헷갈리는 부분이다.

```java
@Transactional
public void callRequiredInnerAndCatch() {
    db.insert("outer");
    try {
        inner.saveRequired("inner", true);   // REQUIRED, 예외 발생
    } catch (IllegalStateException ignored) { }
}   // 정상 리턴했으니 커밋될 것 같지만…
```

```
BEGIN tx1
INSERT outer (in tx1)
INSERT inner (in tx1)        ← 안쪽은 같은 tx1에 "참여"
MARK ROLLBACK-ONLY tx1       ← 안쪽 프록시가 예외를 보고 tx1에 "커밋 금지" 도장을 찍음
ROLLBACK tx1                 ← 바깥이 커밋하려다 도장을 보고 롤백 + UnexpectedRollbackException
DB: []
```

- 안쪽과 바깥은 **물리적으로 한 트랜잭션**이다. 일부만 커밋할 방법이 없다.
- 안쪽 프록시는 예외가 자기 밖으로 나가는 순간 "이 트랜잭션은 망가졌다"고 표시한다. 바깥에서 catch 해도 표시는 지워지지 않는다.
- 그래서 바깥은 조용히 롤백하는 대신 `UnexpectedRollbackException`을 던져 "커밋된 줄 알았지? 아니야"라고 알려준다.

**해결책**: 안쪽 실패가 바깥에 영향을 주면 안 된다면 → `REQUIRES_NEW`로 분리하거나, 안쪽 메서드 **내부에서** 예외를 처리해 프록시 밖으로 던지지 않는다.

### 2-2. REQUIRES_NEW — 독립된 트랜잭션

```
BEGIN tx1
INSERT outer (in tx1)
SUSPEND tx1                  ← 바깥은 잠시 보류
BEGIN tx2                    ← 안쪽 전용 새 트랜잭션 (DB 커넥션도 하나 더 사용)
INSERT inner (in tx2)
ROLLBACK tx2                 ← 안쪽만 롤백
RESUME tx1
COMMIT tx1
DB: [outer]
```

반대로 안쪽이 커밋한 뒤 바깥이 실패하면 **안쪽 데이터는 남는다** (`REQUIRES_NEW_안쪽은_바깥이_실패해도_커밋된_채로_남는다`).
→ "주문은 실패해도 실패 이력 로그는 남겨야 한다" 같은 경우에 쓴다.

주의: 바깥이 커넥션을 쥔 채로 하나를 더 잡으므로, 동시 요청이 많으면 커넥션 풀이 고갈될 수 있다.

### 2-3. NOT_SUPPORTED — 트랜잭션 밖에서 실행

```
SUSPEND tx1
INSERT inner (auto-commit)   ← 트랜잭션이 없으니 쿼리마다 즉시 반영
RESUME tx1
ROLLBACK tx1                 ← 바깥이 롤백해도 inner는 이미 반영됨
DB: [inner]
```

### 2-4. MANDATORY — "혼자서는 호출하지 마"

```java
inner.saveMandatory("inner");   // 바깥 트랜잭션 없음 → IllegalTransactionStateException
outer.callMandatory();          // 바깥 안에서 호출 → 정상
```

이 프로젝트의 `EventOutbox.record()`가 바로 이 방식이다 (`src/main/java/com/example/shop/event/EventOutbox.java`).
이벤트는 **주문 변경과 같은 트랜잭션**에 저장돼야 하므로, 실수로 트랜잭션 밖에서 부르면 아예 실패하게 막아둔 것이다.

---

## 3. 자주 하는 실수 — 같은 클래스 내부 호출(self-invocation)

```java
public void callOwnTransactionalMethod(String row) {   // @Transactional 없음
    saveThenThrowRuntime(row);                        // == this.saveThenThrowRuntime(row)
}

@Transactional
public void saveThenThrowRuntime(String row) { ... throw new IllegalStateException(); }
```

```
INSERT A (auto-commit)       ← BEGIN 자체가 없다
DB: [A]                      ← 예외가 났는데 롤백 안 됨!
```

`this`는 프록시가 아니라 **진짜 객체**라서, 프록시를 거치지 않으니 `@Transactional`이 무시된다.

**해결책**:
- 트랜잭션 메서드를 **다른 빈으로 분리**한다 (이 예제의 `OuterService` → `InnerService` 구조).
- 바깥 메서드에 `@Transactional`을 붙인다.
- 또는 `TransactionTemplate`으로 코드에서 직접 트랜잭션을 연다 (아래 5장).

같은 이유로 **`private` 메서드**에 붙인 `@Transactional`도 동작하지 않는다 (프록시가 가로챌 수 없다).

---

## 4. readOnly

```java
@Transactional(readOnly = true)
```

```
BEGIN tx1 (readOnly)
```

- 트랜잭션 매니저에게 "읽기만 한다"는 힌트를 준다. 실제 효과는 매니저/DB가 결정한다.
- JPA(Hibernate): flush를 하지 않아 변경 감지(dirty checking) 비용이 줄고, 엔티티를 수정해도 DB에 반영되지 않는다.
- MySQL: JDBC 커넥션이 read-only로 설정되어 쓰기 쿼리는 오류가 난다. (replica 라우팅에도 이 플래그를 쓴다)
- 이 프로젝트: `DeadLetterRetryService.recent()`, `OutboxRecoveryService.recentFailed()` 등 조회 메서드에 쓰였다.

---

## 5. 이 프로젝트에서 찾아볼 실제 코드

| 위치 | 사용 방식 | 왜 |
|---|---|---|
| `event/EventOutbox.java` | `@Transactional(propagation = MANDATORY)` | 이벤트 저장은 반드시 도메인 변경과 같은 트랜잭션 |
| `event/IdempotentEventProcessor.java` | `TransactionTemplate.executeWithoutResult(...)` | 처리 기록(`processed_events`)과 비즈니스 처리를 한 트랜잭션으로. 실패 시 함께 롤백 |
| `event/OutboxRelay.java` | `TransactionTemplate` + `setIsolationLevel(READ_COMMITTED)` | 격리 수준을 낮춰 gap lock이 주문 insert를 막지 않게 |
| `event/DeadLetterRetryService.java` | `@Transactional` / `@Transactional(readOnly = true)` | 쓰기/조회 구분 |

`TransactionTemplate`은 어노테이션 대신 **코드로** 트랜잭션 범위를 지정하는 방법이다.

```java
transaction.executeWithoutResult(status -> {
    // 이 블록 안이 하나의 트랜잭션. 런타임 예외가 나면 롤백.
});
```

어노테이션보다 범위가 눈에 잘 보이고, 프록시를 안 거치므로 self-invocation 문제도 없다.

---

## 한 줄 요약

1. 트랜잭션은 **프록시를 거칠 때만** 걸린다 → 내부 호출·private 메서드는 무시된다.
2. 기본은 **런타임 예외만 롤백**한다. 체크 예외는 `rollbackFor`를 붙여야 롤백된다.
3. `REQUIRED`는 한 몸이다 → 안쪽이 실패하면 catch 해도 전체가 롤백된다.
4. 따로 살아남아야 하면 `REQUIRES_NEW`를 쓴다.
5. 반드시 바깥 트랜잭션 안에서만 불려야 하면 `MANDATORY`를 쓴다.
