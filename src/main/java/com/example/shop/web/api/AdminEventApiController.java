package com.example.shop.web.api;

import com.example.shop.event.ConsumerFailureSwitch;
import com.example.shop.event.DeadLetterRetryService;
import com.example.shop.event.OutboxRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin event APIs (REQ-F-014): dead letter retry (REQ-IF-001 6), FAILED outbox re-queue and the failure switch, which answers 404 where it is
 * disabled (every profile except local and test).
 *
 * 관리자 이벤트 API (REQ-F-014): DLT 재처리 (REQ-IF-001 6), FAILED 아웃박스 재대기, 장애 스위치. 장애 스위치는
 * 비활성화된 곳(local과 test를 뺀 모든 프로필)에서 404로 응답한다.
 */
@RestController
public class AdminEventApiController {

    private static final int MAX_FORCED_FAILURES = 100;

    private final DeadLetterRetryService deadLetters;
    private final OutboxRecoveryService outboxRecovery;
    private final ConsumerFailureSwitch failureSwitch;

    public AdminEventApiController(DeadLetterRetryService deadLetters, OutboxRecoveryService outboxRecovery,
                                   ConsumerFailureSwitch failureSwitch) {
        this.deadLetters = deadLetters;
        this.outboxRecovery = outboxRecovery;
        this.failureSwitch = failureSwitch;
    }

    /**
     * 404 unknown id, 409 already retried or not an intact envelope of our topics.
     *
     * 없는 id면 404, 이미 재처리했거나 우리 토픽의 온전한 envelope이 아니면 409.
     */
    @PostMapping("/api/admin/dlt/{id}/retry")
    public RetryResponse retry(@PathVariable long id) {
        deadLetters.retry(id);
        return new RetryResponse(id, true);
    }

    /**
     * FAILED outbox row -> PENDING. 404 unknown id, 409 not FAILED (already re-queued or never failed).
     *
     * FAILED 아웃박스 행 -> PENDING. 없는 id면 404, FAILED가 아니면(이미 재대기했거나 실패한 적 없음) 409.
     */
    @PostMapping("/api/admin/outbox/{id}/requeue")
    public RetryResponse requeueOutboxEvent(@PathVariable long id) {
        outboxRecovery.requeue(id);
        return new RetryResponse(id, true);
    }

    @PostMapping("/api/admin/failure-switch")
    public Map<String, Integer> armFailureSwitch(@Valid @RequestBody FailureSwitchRequest request) {
        failureSwitch.arm(request.consumerGroup(), request.count());
        return failureSwitch.remainingFailures();
    }

    public record RetryResponse(long id, boolean requeued) {
    }

    public record FailureSwitchRequest(@NotBlank String consumerGroup,
                                       @NotNull @Min(0) @Max(MAX_FORCED_FAILURES) Integer count) {
    }
}
