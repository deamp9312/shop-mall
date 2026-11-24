package com.example.shop.tran;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Transactional} 동작 학습용 테스트. Docker·DB 없이 돈다.
 * 각 테스트가 끝나면 트랜잭션 로그를 콘솔에 출력하므로 실행해서 순서를 눈으로 확인해 볼 것.
 * 설명은 같은 폴더의 README.md 참고.
 */
@SpringJUnitConfig(TransactionalBehaviorTest.Config.class)
class TransactionalBehaviorTest {

    /** Spring Boot 전체가 아니라, 트랜잭션 프록시에 필요한 최소한의 빈만 띄운다. */
    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        FakeTransactionManager transactionManager() {
            return new FakeTransactionManager();
        }

        @Bean
        InnerService innerService(FakeTransactionManager db) {
            return new InnerService(db);
        }

        @Bean
        OuterService outerService(FakeTransactionManager db, InnerService inner) {
            return new OuterService(db, inner);
        }
    }

    @Autowired
    FakeTransactionManager db;
    @Autowired
    OuterService outer;
    @Autowired
    InnerService inner;

    @BeforeEach
    void reset() {
        db.reset();
    }

    @AfterEach
    void printLog(TestInfo info) {
        System.out.println("[" + info.getDisplayName() + "] " + db.log() + " => DB: " + db.committedRows());
    }

    // ---- 1. 예외 종류에 따른 커밋/롤백 ----

    @Test
    void 예외가_없으면_커밋된다() {
        outer.save("A");

        assertThat(db.committedRows()).containsExactly("A");
        assertThat(db.log()).containsExactly("BEGIN tx1", "INSERT A (in tx1)", "COMMIT tx1");
    }

    @Test
    void 런타임_예외면_롤백된다() {
        assertThatThrownBy(() -> outer.saveThenThrowRuntime("A")).isInstanceOf(IllegalStateException.class);

        assertThat(db.committedRows()).isEmpty();
        assertThat(db.log()).containsExactly("BEGIN tx1", "INSERT A (in tx1)", "ROLLBACK tx1");
    }

    @Test
    void 체크_예외는_기본적으로_커밋된다() {
        assertThatThrownBy(() -> outer.saveThenThrowChecked("A")).isInstanceOf(Exception.class);

        assertThat(db.committedRows()).containsExactly("A"); // 예외가 났는데도 저장됨!
        assertThat(db.log()).containsExactly("BEGIN tx1", "INSERT A (in tx1)", "COMMIT tx1");
    }

    @Test
    void rollbackFor를_주면_체크_예외도_롤백된다() {
        assertThatThrownBy(() -> outer.saveThenThrowCheckedWithRollbackFor("A")).isInstanceOf(Exception.class);

        assertThat(db.committedRows()).isEmpty();
    }

    // ---- 2. 전파(propagation) ----

    @Test
    void REQUIRED_안쪽_실패를_catch해도_전체가_롤백된다() {
        assertThatThrownBy(() -> outer.callRequiredInnerAndCatch()).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(db.committedRows()).isEmpty();
        assertThat(db.log()).containsExactly(
                "BEGIN tx1",
                "INSERT outer (in tx1)",
                "INSERT inner (in tx1)",     // 같은 tx1에 참여
                "MARK ROLLBACK-ONLY tx1",    // 안쪽이 실패하면서 tx1에 "커밋 금지" 표시
                "ROLLBACK tx1");             // 바깥이 커밋하려 했지만 표시 때문에 롤백 + 예외
    }

    @Test
    void REQUIRES_NEW_안쪽_실패는_안쪽만_롤백된다() {
        outer.callRequiresNewInnerAndCatch();

        assertThat(db.committedRows()).containsExactly("outer");
        assertThat(db.log()).containsExactly(
                "BEGIN tx1",
                "INSERT outer (in tx1)",
                "SUSPEND tx1",               // 바깥 트랜잭션 보류
                "BEGIN tx2",                 // 안쪽 전용 새 트랜잭션
                "INSERT inner (in tx2)",
                "ROLLBACK tx2",
                "RESUME tx1",
                "COMMIT tx1");
    }

    @Test
    void REQUIRES_NEW_안쪽은_바깥이_실패해도_커밋된_채로_남는다() {
        assertThatThrownBy(() -> outer.callRequiresNewThenFail()).isInstanceOf(IllegalStateException.class);

        assertThat(db.committedRows()).containsExactly("inner");
        assertThat(db.log()).containsExactly(
                "BEGIN tx1", "INSERT outer (in tx1)",
                "SUSPEND tx1", "BEGIN tx2", "INSERT inner (in tx2)", "COMMIT tx2", "RESUME tx1",
                "ROLLBACK tx1");
    }

    @Test
    void NOT_SUPPORTED는_트랜잭션_없이_바로_반영된다() {
        assertThatThrownBy(() -> outer.callNotSupportedThenFail()).isInstanceOf(IllegalStateException.class);

        assertThat(db.committedRows()).containsExactly("inner");
        assertThat(db.log()).containsExactly(
                "BEGIN tx1", "INSERT outer (in tx1)",
                "SUSPEND tx1", "INSERT inner (auto-commit)", "RESUME tx1",
                "ROLLBACK tx1");
    }

    @Test
    void MANDATORY는_바깥_트랜잭션_없이_부르면_예외다() {
        assertThatThrownBy(() -> inner.saveMandatory("inner")).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(db.committedRows()).isEmpty();

        outer.callMandatory(); // 바깥 트랜잭션 안에서는 정상
        assertThat(db.committedRows()).containsExactly("inner");
    }

    // ---- 3. 자주 하는 실수 ----

    @Test
    void 같은_클래스_내부_호출은_트랜잭션이_적용되지_않는다() {
        assertThatThrownBy(() -> outer.callOwnTransactionalMethod("A")).isInstanceOf(IllegalStateException.class);

        assertThat(db.committedRows()).containsExactly("A"); // 예외가 났는데 롤백이 안 됨!
        assertThat(db.log()).containsExactly("INSERT A (auto-commit)"); // BEGIN 자체가 없다
    }

    // ---- 4. readOnly ----

    @Test
    void readOnly는_트랜잭션에_읽기전용_표시를_전달한다() {
        assertThat(outer.isReadOnlyInside()).isTrue();
        assertThat(db.log()).containsExactly("BEGIN tx1 (readOnly)", "COMMIT tx1");
    }
}
