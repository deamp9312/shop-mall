package com.example.shop.tran;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 학습용 가짜 트랜잭션 매니저 겸 가짜 DB이다.
 * <p>
 * "참여할까? 새로 열까? 롤백할까?" 같은 판단은 전부 부모 클래스인 Spring의 {@link AbstractPlatformTransactionManager}가 한다.
 * 이 클래스는 실제 DB에 BEGIN / COMMIT / ROLLBACK을 보내는 마지막 단계만 흉내 내고, 그 순서를 {@link #log()}에 남긴다.
 * 실제 프로젝트의 JpaTransactionManager(MySQL)도 같은 부모 클래스를 쓰므로 동작 규칙이 똑같다.
 */
public class FakeTransactionManager extends AbstractPlatformTransactionManager {

    /** 커밋되어 "DB에 실제로 남은" 행들. */
    private final List<String> committedRows = new ArrayList<>();
    /** BEGIN / INSERT / COMMIT / ROLLBACK 등이 일어난 순서. */
    private final List<String> log = new ArrayList<>();
    /** 지금 스레드에서 진행 중인 트랜잭션. Spring도 ThreadLocal로 현재 트랜잭션을 관리한다. */
    private final ThreadLocal<FakeTx> current = new ThreadLocal<>();
    private int sequence;

    /** 가짜 INSERT. 트랜잭션 안이면 커밋할 때까지 보류하고, 트랜잭션 밖이면 바로 반영한다(auto-commit). */
    public void insert(String row) {
        FakeTx tx = current.get();
        if (tx == null) {
            committedRows.add(row);
            log.add("INSERT " + row + " (auto-commit)");
        } else {
            tx.pendingRows.add(row);
            log.add("INSERT " + row + " (in " + tx.id + ")");
        }
    }

    public List<String> committedRows() {
        return List.copyOf(committedRows);
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    public void reset() {
        committedRows.clear();
        log.clear();
        current.remove();
        sequence = 0;
    }

    // ---- 아래는 Spring이 필요할 때 호출하는 콜백이다 ----

    /** 메서드 진입 시 매번 호출된다. 이미 진행 중인 트랜잭션이 있으면 그것을 담아 돌려준다. */
    @Override
    protected Object doGetTransaction() {
        return new TxHandle(current.get());
    }

    /** true면 Spring은 propagation 설정에 따라 "참여 / 보류 후 새로 시작 / 예외" 중 하나를 고른다. */
    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TxHandle) transaction).tx != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        FakeTx tx = new FakeTx("tx" + (++sequence));
        ((TxHandle) transaction).tx = tx;
        current.set(tx);
        log.add("BEGIN " + tx.id + (definition.isReadOnly() ? " (readOnly)" : ""));
    }

    /** REQUIRES_NEW / NOT_SUPPORTED가 기존 트랜잭션을 잠시 옆으로 치워둘 때 호출된다. */
    @Override
    protected Object doSuspend(Object transaction) {
        FakeTx suspended = current.get();
        ((TxHandle) transaction).tx = null;
        current.remove();
        log.add("SUSPEND " + suspended.id);
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        FakeTx tx = (FakeTx) suspendedResources;
        current.set(tx);
        log.add("RESUME " + tx.id);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        FakeTx tx = txOf(status);
        committedRows.addAll(tx.pendingRows);
        log.add("COMMIT " + tx.id);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        log.add("ROLLBACK " + txOf(status).id);
    }

    /** 참여 중인(REQUIRED) 안쪽 메서드가 실패하면, 바깥 트랜잭션에 "커밋하면 안 됨" 표시를 남긴다. */
    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        FakeTx tx = txOf(status);
        tx.rollbackOnly = true;
        log.add("MARK ROLLBACK-ONLY " + tx.id);
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }

    private static FakeTx txOf(DefaultTransactionStatus status) {
        return ((TxHandle) status.getTransaction()).tx;
    }

    /** 실제 DB 트랜잭션 하나. 커밋 전까지 쓴 행을 보관한다. */
    private static final class FakeTx {
        private final String id;
        private final List<String> pendingRows = new ArrayList<>();
        private boolean rollbackOnly;

        private FakeTx(String id) {
            this.id = id;
        }
    }

    /** Spring이 메서드 호출마다 들고 다니는 트랜잭션 객체. rollback-only 여부를 Spring이 여기서 확인한다. */
    private static final class TxHandle implements SmartTransactionObject {
        private FakeTx tx;

        private TxHandle(FakeTx tx) {
            this.tx = tx;
        }

        @Override
        public boolean isRollbackOnly() {
            return tx != null && tx.rollbackOnly;
        }

        @Override
        public void flush() {
        }
    }
}
