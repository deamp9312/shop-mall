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

    /** 404 unknown id, 409 already retried or not an intact envelope of our topics. */
    @PostMapping("/api/admin/dlt/{id}/retry")
    public RetryResponse retry(@PathVariable long id) {
        deadLetters.retry(id);
        return new RetryResponse(id, true);
    }

    /** FAILED outbox row -> PENDING. 404 unknown id, 409 not FAILED (already re-queued or never failed). */
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
