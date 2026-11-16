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
 * 데모용 Kafka 이벤트 화면(REQ-F-014, ADMIN 전용). 한 주문의 발행·소비 이벤트를 시간순으로 보여주고, [재처리]가 있는
 * dead letter, [다시 발행]이 있는 FAILED outbox 행과 그 행이 붙잡고 있는 후속 행, 활성화된 환경(local 프로필)에서는
 * 장애 스위치를 보여준다. 토픽 자체는 여기가 아니라 kafka-ui에서 확인한다.
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
