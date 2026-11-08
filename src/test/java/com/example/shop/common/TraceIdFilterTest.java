package com.example.shop.common;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void putsTraceIdInMdcDuringRequestAndClearsItAfterwards() throws Exception {
        AtomicReference<String> traceIdInChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/"), response,
                (request, res) -> traceIdInChain.set(MDC.get(MdcKeys.TRACE_ID)));

        assertThat(traceIdInChain.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(traceIdInChain.get());
        assertThat(MDC.get(MdcKeys.TRACE_ID)).isNull();
    }

    @Test
    void everyRequestGetsItsOwnTraceId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/"), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("GET", "/"), second, (req, res) -> { });

        assertThat(first.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isNotEqualTo(second.getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }
}
