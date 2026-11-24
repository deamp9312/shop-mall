package com.example.shop.tran;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 테스트 시나리오마다 메서드 하나. 각 메서드가 바깥(outer) 트랜잭션 역할을 한다. */
public class OuterService {

    private final FakeTransactionManager db;
    private final InnerService inner;

    public OuterService(FakeTransactionManager db, InnerService inner) {
        this.db = db;
        this.inner = inner;
    }

    // ---- 1. 예외 종류에 따른 커밋/롤백 ----

    @Transactional
    public void save(String row) {
        db.insert(row);
    }

    @Transactional
    public void saveThenThrowRuntime(String row) {
        db.insert(row);
        throw new IllegalStateException("런타임 예외");
    }

    /** 체크 예외는 기본 설정에서 롤백하지 않는다! */
    @Transactional
    public void saveThenThrowChecked(String row) throws Exception {
        db.insert(row);
        throw new Exception("체크 예외");
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveThenThrowCheckedWithRollbackFor(String row) throws Exception {
        db.insert(row);
        throw new Exception("체크 예외");
    }

    // ---- 2. 전파(propagation) ----

    /** 안쪽 REQUIRED 메서드의 예외를 바깥에서 catch 해도 이미 늦었다. */
    @Transactional
    public void callRequiredInnerAndCatch() {
        db.insert("outer");
        try {
            inner.saveRequired("inner", true);
        } catch (IllegalStateException ignored) {
            // 예외는 삼켰지만 트랜잭션에는 이미 rollback-only 표시가 남아 있다
        }
    }

    @Transactional
    public void callRequiresNewInnerAndCatch() {
        db.insert("outer");
        try {
            inner.saveRequiresNew("inner", true);
        } catch (IllegalStateException ignored) {
            // 안쪽은 별도 트랜잭션이었으므로 안쪽만 롤백된다
        }
    }

    @Transactional
    public void callRequiresNewThenFail() {
        db.insert("outer");
        inner.saveRequiresNew("inner", false);
        throw new IllegalStateException("outer 실패");
    }

    @Transactional
    public void callNotSupportedThenFail() {
        db.insert("outer");
        inner.saveNotSupported("inner");
        throw new IllegalStateException("outer 실패");
    }

    @Transactional
    public void callMandatory() {
        inner.saveMandatory("inner");
    }

    // ---- 3. 자주 하는 실수: 같은 클래스 내부 호출 ----

    /** @Transactional 이 없는 메서드에서 this.xxx() 로 부르면 프록시를 거치지 않아 트랜잭션이 안 열린다. */
    public void callOwnTransactionalMethod(String row) {
        saveThenThrowRuntime(row); // == this.saveThenThrowRuntime(row)
    }

    // ---- 4. readOnly ----

    @Transactional(readOnly = true)
    public boolean isReadOnlyInside() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
}
