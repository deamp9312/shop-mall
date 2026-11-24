package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자의 dead letter [재처리]이다 (REQ-F-014 2, REQ-IF-001 6). 저장된 envelope는 KafkaTemplate으로 직접 보내지 않고
 * 항상 outbox를 거쳐 원래 topic으로 돌아간다. dead letter마다 한 번만 재시도할 수 있으며, 다시 실패하면 새 dead
 * letter로 돌아온다.
 */
@Service
public class DeadLetterRetryService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRetryService.class);

    private final DeadLetterEventRepository deadLetters;
    private final EventOutbox outbox;
    private final ObjectMapper objectMapper;

    public DeadLetterRetryService(DeadLetterEventRepository deadLetters, EventOutbox outbox,
                                  ObjectMapper objectMapper) {
        this.deadLetters = deadLetters;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<DeadLetterEvent> recent() {
        return deadLetters.findTop50ByOrderByIdDesc();
    }

    /**
     * 관리자 [재처리] 버튼 (POST /api/admin/dlt/{id}/retry).
     * <pre>
     * [IN]  deadLetterId=5
     *       → dead_letter_events(id=5): original_topic="shop.order.created",
     *         payload='{"eventId":"9b1deb4d-...","aggregateId":7,...}'
     * [OUT] EventOutbox.republish("9b1deb4d-...", "shop.order.created", "7", payload)
     *       → outbox_events 행 PENDING → OutboxRelay가 shop.order.created로 다시 produce
     *       dead_letter_events(id=5).retried_at=now  (두 번째 클릭은 DEAD_LETTER_ALREADY_RETRIED)
     * </pre>
     */
    @Transactional
    public void retry(long deadLetterId) {
        DeadLetterEvent deadLetter = deadLetters.findById(deadLetterId)
                .orElseThrow(() -> new ShopException(ErrorCode.DEAD_LETTER_NOT_FOUND));
        if (deadLetter.isRetried()) {
            throw new ShopException(ErrorCode.DEAD_LETTER_ALREADY_RETRIED);
        }
        RetryTarget target = retryTargetOf(deadLetter);
        outbox.republish(target.eventId(), deadLetter.getOriginalTopic(), target.messageKey(),
                deadLetter.getPayload());
        deadLetter.markRetried();
        log.info("dead letter retried: id={} topic={} eventId={}", deadLetterId, deadLetter.getOriginalTopic(),
                target.eventId());
    }

    /** 우리 topic의 온전한 envelope만 다시 보낼 수 있다. 형식이 잘못된 record는 또 실패할 뿐이다. */
    private RetryTarget retryTargetOf(DeadLetterEvent deadLetter) {
        if (!EventTopics.ALL.contains(deadLetter.getOriginalTopic())) {
            throw new ShopException(ErrorCode.DEAD_LETTER_NOT_RETRYABLE);
        }
        try {
            JsonNode envelope = objectMapper.readTree(deadLetter.getPayload());
            JsonNode eventId = envelope == null ? null : envelope.get("eventId");
            JsonNode aggregateId = envelope == null ? null : envelope.get("aggregateId");
            if (eventId == null || !eventId.isTextual() || aggregateId == null || !aggregateId.canConvertToLong()) {
                throw new ShopException(ErrorCode.DEAD_LETTER_NOT_RETRYABLE);
            }
            return new RetryTarget(eventId.asText(), String.valueOf(aggregateId.asLong()));
        } catch (JsonProcessingException malformed) {
            throw new ShopException(ErrorCode.DEAD_LETTER_NOT_RETRYABLE);
        }
    }

    /** message key는 orderId이며, envelope에는 aggregateId로 들어 있다. */
    private record RetryTarget(String eventId, String messageKey) {
    }
}
