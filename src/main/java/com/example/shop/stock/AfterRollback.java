package com.example.shop.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Redis is not part of the DB transaction: a Redis change made before the commit is undone by hand if the transaction
 * rolls back. The compensation runs after the rollback has released the DB locks, so a resync may already have
 * rewritten the counter from the database; callers therefore pass the resync epoch they read inside the transaction
 * ({@link AvailableStockCounter#addUnlessResynced}).
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
