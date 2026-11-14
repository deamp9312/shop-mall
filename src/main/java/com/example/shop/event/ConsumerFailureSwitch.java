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
 *
 * 관리자 이벤트 화면의 장애 시연 스위치 (REQ-F-014 3). 컨슈머 그룹에 N을 걸면 그 그룹이 처리하는 다음 N개의 이벤트가 매
 * 전달 시도마다 실패하므로, 각 이벤트가 1초/2초/4초 재시도를 거쳐 DLT로 간다. 같은 이벤트를 나중에 다시 전달하면(관리자
 * 재처리) 통과한다.
 * <p>
 * {@code shop.demo.failure-switch-enabled}가 true인 곳에서만 동작한다. 시연용 local 프로필과 검증용 test 프로필이다.
 * 상태는 메모리에 있으므로 인스턴스 하나에만 속하고 재시작하면 사라진다.
 */
@Component
public class ConsumerFailureSwitch {

    private static final Logger log = LoggerFactory.getLogger(ConsumerFailureSwitch.class);

    private final boolean enabled;
    /**
     * consumer group -> events still to fail.
     *
     * 컨슈머 그룹 -> 아직 실패시킬 이벤트 수.
     */
    private final Map<String, Integer> remaining = new ConcurrentHashMap<>();
    /**
     * group + eventId of an event being failed -> attempts failed so far.
     *
     * 실패 중인 이벤트의 group + eventId -> 지금까지 실패한 시도 수.
     */
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    public ConsumerFailureSwitch(@Value("${shop.demo.failure-switch-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param count 0 disarms the group
     *
     * count: 0이면 그 그룹의 스위치를 해제한다
     */
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

    /**
     * Called by {@link IdempotentEventProcessor} right before the handler, inside its transaction.
     *
     * {@link IdempotentEventProcessor}가 핸들러 바로 앞에서, 자기 트랜잭션 안에서 호출한다.
     */
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

    /**
     * Retryable on purpose: the demo shows the retries before the DLT.
     *
     * 일부러 재시도 가능하게 둔다. 시연에서 DLT 전에 재시도를 보여 주기 위해서다.
     */
    static class ForcedConsumerFailure extends RuntimeException {

        ForcedConsumerFailure(String consumerGroup, String eventId, int attempt) {
            super("forced failure by the demo switch: group=" + consumerGroup + " eventId=" + eventId
                    + " attempt=" + attempt);
        }
    }
}
