package com.example.shop.web.admin;

import com.example.shop.event.ConsumerFailureSwitch;
import com.example.shop.event.ConsumerGroups;
import com.example.shop.event.DeadLetterRetryService;
import com.example.shop.event.EventTraceQuery;
import com.example.shop.event.OutboxRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Kafka event screen for the demo (REQ-F-014, ADMIN only): one order's published and consumed events in time order,
 * the dead letters with [재처리], FAILED outbox rows with [다시 발행] and the later rows they hold back, and the failure switch where it is enabled (local profile). Topics themselves are
 * inspected in kafka-ui, not here.
 */
@Controller
public class AdminEventController {

    private final EventTraceQuery traces;
    private final DeadLetterRetryService deadLetters;
    private final OutboxRecoveryService outboxRecovery;
    private final ConsumerFailureSwitch failureSwitch;

    public AdminEventController(EventTraceQuery traces, DeadLetterRetryService deadLetters,
                                OutboxRecoveryService outboxRecovery, ConsumerFailureSwitch failureSwitch) {
        this.traces = traces;
        this.deadLetters = deadLetters;
        this.outboxRecovery = outboxRecovery;
        this.failureSwitch = failureSwitch;
    }

    @GetMapping("/admin/events")
    public String events(@RequestParam(required = false) Long orderId, Model model) {
        model.addAttribute("orderId", orderId);
        model.addAttribute("trace", orderId == null ? List.of() : traces.traceOf(orderId));
        model.addAttribute("deadLetters", deadLetters.recent());
        model.addAttribute("failedOutboxEvents", outboxRecovery.recentFailed());
        model.addAttribute("heldBackOutboxEvents", outboxRecovery.heldBackCount());
        model.addAttribute("failureSwitchEnabled", failureSwitch.isEnabled());
        model.addAttribute("remainingFailures", failureSwitch.remainingFailures());
        model.addAttribute("consumerGroups", ConsumerGroups.MODULE_GROUPS);
        return "admin/events";
    }
}
