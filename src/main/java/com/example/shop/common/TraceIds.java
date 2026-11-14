package com.example.shop.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * traceId values (REQ-NF-003): one per HTTP request, carried by events and restored in consumers.
 *
 * traceId 값 (REQ-NF-003): HTTP 요청마다 하나씩 만들고, 이벤트에 실어 보내며, 컨슈머에서 복원한다.
 */
public final class TraceIds {

    private TraceIds() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * The traceId of the current request or consumed event; a new one when the work did not start from either.
     *
     * 현재 요청이나 소비 중인 이벤트의 traceId. 둘 중 어디에서도 시작하지 않은 작업이면 새로 만든다.
     */
    public static String currentOrNew() {
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        return traceId != null ? traceId : newTraceId();
    }

    /**
     * Runs work under another traceId and restores the current one afterwards. Used when a request continues a flow
     * that an earlier request started (the payment approval continues the order's trace).
     *
     * 다른 traceId로 작업을 실행하고 끝나면 현재 traceId로 되돌린다. 앞선 요청이 시작한 흐름을 이어 가는 요청에서 쓴다
     * (결제 승인은 주문의 trace를 이어 간다).
     */
    public static <T> T callWithTraceId(String traceId, Supplier<T> work) {
        String current = MDC.get(MdcKeys.TRACE_ID);
        MDC.put(MdcKeys.TRACE_ID, traceId);
        try {
            return work.get();
        } finally {
            if (current == null) {
                MDC.remove(MdcKeys.TRACE_ID);
            } else {
                MDC.put(MdcKeys.TRACE_ID, current);
            }
        }
    }
}
