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
 * Admin [재처리] of a dead letter (REQ-F-014 2, REQ-IF-001 6): the stored envelope goes back to its original topic
 * through the outbox, never with a direct KafkaTemplate send. Each dead letter can be retried once; if it fails again it
 * comes back as a new dead letter.
 *
 * DLT 이벤트의 관리자 [재처리] (REQ-F-014 2, REQ-IF-001 6): 저장된 envelope을 KafkaTemplate으로 직접 보내지 않고 아웃박스를
 * 거쳐 원래 토픽으로 돌려보낸다. DLT 이벤트마다 한 번만 재처리할 수 있으며, 다시 실패하면 새 DLT 이벤트로 돌아온다.
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

    /**
     * Only an intact envelope of one of our topics can be sent again; a malformed record would just fail again.
     *
     * 우리 토픽의 온전한 envelope만 다시 보낼 수 있다. 형식이 깨진 레코드는 다시 보내도 또 실패할 뿐이다.
     */
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

    /**
     * The message key is the orderId, which the envelope carries as aggregateId.
     *
     * 메시지 키는 orderId이며, envelope에는 aggregateId로 들어 있다.
     */
    private record RetryTarget(String eventId, String messageKey) {
    }
}
