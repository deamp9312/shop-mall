package com.example.shop.common;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/** traceId 값 (REQ-NF-003): HTTP 요청마다 하나씩 만들고, 이벤트에 실어 보내며 컨슈머에서 복원한다. */
public final class TraceIds {

    private TraceIds() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 현재 요청 또는 소비 중인 이벤트의 traceId다. 둘 다에서 시작한 작업이 아니면 새로 만든다. */
    public static String currentOrNew() {
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        return traceId != null ? traceId : newTraceId();
    }

    /**
     * 다른 traceId로 작업을 실행하고, 끝나면 현재 traceId를 복원한다. 이전 요청이 시작한 흐름을 이어받는 요청에서
     * 사용한다 (결제 승인은 주문의 trace를 이어간다).
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
