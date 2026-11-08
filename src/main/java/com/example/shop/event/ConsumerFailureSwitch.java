package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Failure demo switch of the admin event screen (REQ-F-014 3). Armed for a consumer group with N, it makes the next N
 * events that group handles fail on every delivery attempt, so each one goes through the 1s/2s/4s retries to the DLT.
 * A later delivery of the same event (the admin retry) passes.
 * <p>
 * Works only where {@code shop.demo.failure-switch-enabled} is true: the local profile for the demo and the test profile
 * for its verification. State is in memory, so it belongs to one instance and is gone after a restart.
 */
@Component
public class ConsumerFailureSwitch {

    private static final Logger log = LoggerFactory.getLogger(ConsumerFailureSwitch.class);

    private final boolean enabled;
    /** consumer group -> events still to fail. */
    private final Map<String, Integer> remaining = new ConcurrentHashMap<>();
    /** group + eventId of an event being failed -> attempts failed so far. */
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    public ConsumerFailureSwitch(@Value("${shop.demo.failure-switch-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @param count 0 disarms the group */
    public void arm(String consumerGroup, int count) {
        if (!enabled) {
            throw new ShopException(ErrorCode.NOT_FOUND);
        }
        if (!ConsumerGroups.MODULE_GROUPS.contains(consumerGroup) || count < 0) {
            throw new ShopException(ErrorCode.INVALID_INPUT);
        }
        if (count == 0) {
            remaining.remove(consumerGroup);
        } else {
            remaining.put(consumerGroup, count);
        }
        log.warn("failure switch armed: group={} nextEvents={}", consumerGroup, count);
    }

    public Map<String, Integer> remainingFailures() {
        return new TreeMap<>(remaining);
    }

    /** Called by {@link IdempotentEventProcessor} right before the handler, inside its transaction. */
    void failIfArmed(String consumerGroup, String eventId) {
        if (!enabled) {
            return;
        }
        String eventKey = consumerGroup + ":" + eventId;
        if (!failedAttempts.containsKey(eventKey) && !takeOne(consumerGroup)) {
            return;
        }
        int attempt = failedAttempts.merge(eventKey, 1, Integer::sum);
        if (attempt >= KafkaConfig.DELIVERY_ATTEMPTS) {
            failedAttempts.remove(eventKey);
        }
        throw new ForcedConsumerFailure(consumerGroup, eventId, attempt);
    }

    private boolean takeOne(String consumerGroup) {
        AtomicBoolean taken = new AtomicBoolean();
        remaining.computeIfPresent(consumerGroup, (group, count) -> {
            taken.set(true);
            return count > 1 ? count - 1 : null;
        });
        return taken.get();
    }

    /** Retryable on purpose: the demo shows the retries before the DLT. */
    static class ForcedConsumerFailure extends RuntimeException {

        ForcedConsumerFailure(String consumerGroup, String eventId, int attempt) {
            super("forced failure by the demo switch: group=" + consumerGroup + " eventId=" + eventId
                    + " attempt=" + attempt);
        }
    }
}
