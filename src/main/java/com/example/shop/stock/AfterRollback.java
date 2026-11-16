package com.example.shop.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Redis는 DB 트랜잭션에 속하지 않는다. 커밋 전에 한 Redis 변경은 트랜잭션이 롤백되면 직접 되돌린다. 보상은
 * 롤백으로 DB 락이 풀린 뒤에 실행되므로, 그 사이 재동기화가 DB 기준으로 카운터를 이미 다시 썼을 수 있다. 그래서
 * 호출자는 트랜잭션 안에서 읽은 resync epoch를 넘긴다 ({@link AvailableStockCounter#addUnlessResynced}).
 */
final class AfterRollback {

    private static final Logger log = LoggerFactory.getLogger(AfterRollback.class);

    private AfterRollback() {
    }

    static void undo(AvailableStockCounter counter, Map<Long, Integer> applied, String epochBefore) {
        run(() -> {
            if (!counter.addUnlessResynced(AvailableStockCounter.negated(applied), epochBefore)) {
                log.info("rollback compensation skipped, a resync already rewrote the counters: {}", applied);
            }
        });
    }

    private static void run(Runnable compensation) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    compensation.run();
                }
            }
        });
    }
}
