package com.example.shop.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every HTTP request a new traceId in the MDC and echoes it in the response header (REQ-NF-003).
 * A client-supplied id is not trusted, so log lines cannot be forged through a header.
 * The outbox copies it into the event envelope and the Kafka header; consumers restore it (event/TraceRecordInterceptor).
 *
 * 모든 HTTP 요청에 새 traceId를 MDC에 넣고 응답 헤더로도 돌려준다 (REQ-NF-003).
 * 클라이언트가 보낸 id는 믿지 않으므로 헤더로 로그 줄을 위조할 수 없다.
 * 아웃박스가 이 값을 이벤트 envelope과 Kafka 헤더에 복사하고, 컨슈머가 복원한다 (event/TraceRecordInterceptor).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.newTraceId();
        MDC.put(MdcKeys.TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
        }
    }
}
