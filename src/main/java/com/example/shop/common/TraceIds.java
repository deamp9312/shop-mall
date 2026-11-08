package com.example.shop.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/** traceId values (REQ-NF-003): one per HTTP request, carried by events and restored in consumers. */
public final class TraceIds {

    private TraceIds() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** The traceId of the current request or consumed event; a new one when the work did not start from either. */
    public static String currentOrNew() {
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        return traceId != null ? traceId : newTraceId();
    }

    /**
     * Runs work under another traceId and restores the current one afterwards. Used when a request continues a flow
     * that an earlier request started (the payment approval continues the order's trace).
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
