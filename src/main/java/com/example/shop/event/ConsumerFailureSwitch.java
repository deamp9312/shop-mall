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
 * 관리자 이벤트 화면의 장애 시연 스위치이다 (REQ-F-014 3). consumer group에 N으로 설정하면 그 group이 다음에
 * 처리하는 N개 이벤트가 모든 전달 시도에서 실패하므로, 각 이벤트는 1s/2s/4s 재시도를 거쳐 DLT로 간다.
 * 같은 이벤트의 이후 전달(관리자 재처리)은 통과한다.
 * <p>
 * {@code shop.demo.failure-switch-enabled}가 true인 곳, 즉 시연용 local profile과 검증용 test profile에서만 동작한다.
 * 상태는 메모리에 있으므로 인스턴스 하나에 속하며 재시작하면 사라진다.
 */
@Component
public class ConsumerFailureSwitch {

    private static final Logger log = LoggerFactory.getLogger(ConsumerFailureSwitch.class);

    private final boolean enabled;
    /** consumer group -> 아직 실패시킬 이벤트 수. */
    private final Map<String, Integer> remaining = new ConcurrentHashMap<>();
    /** 실패시키는 중인 이벤트의 group + eventId -> 지금까지 실패한 시도 횟수. */
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    public ConsumerFailureSwitch(@Value("${shop.demo.failure-switch-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @param count 0이면 해당 group의 스위치를 해제한다 */
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

    /** {@link IdempotentEventProcessor}가 자신의 트랜잭션 안에서 handler 직전에 호출한다. */
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

    /** 일부러 재시도 가능하게 둔다. 시연에서 DLT 전의 재시도를 보여주기 위해서이다. */
    static class ForcedConsumerFailure extends RuntimeException {

        ForcedConsumerFailure(String consumerGroup, String eventId, int attempt) {
            super("forced failure by the demo switch: group=" + consumerGroup + " eventId=" + eventId
                    + " attempt=" + attempt);
        }
    }
}
