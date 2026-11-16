package com.example.shop.common;

/** 로그 패턴이 출력하는 MDC 키 (REQ-NF-003). */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String ORDER_ID = "orderId";

    private MdcKeys() {
    }
}
