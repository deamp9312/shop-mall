package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * FAILED outbox 행을 관리자가 복구한다 (REQ-F-014, REQ-DA-002 "no loss"). 행은 PENDING으로 돌아가고 relay가 다음
 * poll에서 발행한다. 이 행이 막고 있던 같은 주문의 행들(id가 더 크다)보다 먼저 발행된다.
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

    /** 조건부 update이므로 두 번 클릭해도 한 번만 재등록되고, 두 번째 요청은 409를 받는다. */
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
