package com.example.shop.common;

/**
 * MDC keys printed by the log pattern (REQ-NF-003).
 *
 * 로그 패턴이 출력하는 MDC 키 (REQ-NF-003).
 */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String ORDER_ID = "orderId";

    private MdcKeys() {
    }
}
