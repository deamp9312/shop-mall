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
 *
 * FAILED 아웃박스 행의 관리자 복구 (REQ-F-014, REQ-DA-002 "유실 없음"). 행은 PENDING으로 돌아가고, 릴레이가 다음
 * 폴링에서 이 행을 보낸다. 이 행이 붙잡고 있던 같은 주문의 행들보다 먼저 보낸다(그 행들의 id가 더 크다).
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

    /**
     * Conditional update, so two clicks re-queue once; the second gets 409.
     *
     * 조건부 update이므로 두 번 클릭해도 한 번만 다시 대기열에 넣는다. 두 번째는 409를 받는다.
     */
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
