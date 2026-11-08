package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin recovery of FAILED outbox rows (REQ-F-014, REQ-DA-002 "no loss"): the row goes back to PENDING and the relay
 * sends it on its next poll, before the rows of the same order it held back (they have larger ids).
 */
@Service
public class OutboxRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryService.class);

    private final OutboxEventRepository outbox;

    public OutboxRecoveryService(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<FailedOutboxEvent> recentFailed() {
        return outbox.findRecentFailed();
    }

    @Transactional(readOnly = true)
    public long heldBackCount() {
        return outbox.countHeldBack();
    }

    /** Conditional update, so two clicks re-queue once; the second gets 409. */
    @Transactional
    public void requeue(long outboxEventId) {
        if (outbox.requeueFailed(outboxEventId) == 1) {
            log.info("FAILED outbox event re-queued: id={}", outboxEventId);
            return;
        }
        if (!outbox.existsById(outboxEventId)) {
            throw new ShopException(ErrorCode.OUTBOX_EVENT_NOT_FOUND);
        }
        throw new ShopException(ErrorCode.OUTBOX_EVENT_NOT_FAILED);
    }
}
