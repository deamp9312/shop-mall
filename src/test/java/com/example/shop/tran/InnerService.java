package com.example.shop.tran;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link OuterService}가 호출하는 안쪽 서비스. propagation 별로 메서드를 하나씩 둔다. */
public class InnerService {

    private final FakeTransactionManager db;

    public InnerService(FakeTransactionManager db) {
        this.db = db;
    }

    /** 기본값(REQUIRED): 바깥 트랜잭션이 있으면 거기에 참여하고, 없으면 새로 연다. */
    @Transactional
    public void saveRequired(String row, boolean fail) {
        db.insert(row);
        if (fail) {
            throw new IllegalStateException("inner 실패");
        }
    }

    /** REQUIRES_NEW: 바깥 트랜잭션을 잠시 보류하고 항상 자기만의 새 트랜잭션을 연다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRequiresNew(String row, boolean fail) {
        db.insert(row);
        if (fail) {
            throw new IllegalStateException("inner 실패");
        }
    }

    /** NOT_SUPPORTED: 바깥 트랜잭션을 보류하고 트랜잭션 없이 실행한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void saveNotSupported(String row) {
        db.insert(row);
    }

    /** MANDATORY: 바깥 트랜잭션이 반드시 있어야 한다. 없으면 예외. (프로젝트의 EventOutbox.record 가 이 방식) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveMandatory(String row) {
        db.insert(row);
    }
}
