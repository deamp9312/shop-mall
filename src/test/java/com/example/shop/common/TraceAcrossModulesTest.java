package com.example.shop.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.shop.member.MemberPrincipal;
import com.example.shop.member.MemberRole;
import com.example.shop.support.IntegrationTestSupport;
import com.example.shop.support.OrderFlow;
import com.example.shop.support.OrderFlow.PlacedOrder;
import com.example.shop.support.TestProducts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * REQ-NF-003 verification 1): grepping the traceId of the order request finds log lines of the order, payment, stock
 * and notification modules from order placement to PAID, although the approval is a separate HTTP request and the
 * consumers run on Kafka listener threads.
 *
 * REQ-NF-003 검증 1): 주문 요청의 traceId로 grep하면 주문 생성부터 PAID까지 주문, 결제, 재고, 알림 모듈의 로그 줄이
 * 나온다. 승인은 별도 HTTP 요청이고 컨슈머는 Kafka 리스너 스레드에서 실행되는데도 그렇다.
 */
class TraceAcrossModulesTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_490L;
    private static final long PRODUCT_ID = 9_491L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_491L, "trace-test@shop.local", "추적", MemberRole.USER, null);
    private static final String MODULE_PREFIX = "com.example.shop.";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TraceIdFilter traceIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final TraceCapture capture = new TraceCapture();
    private TestProducts products;
    private OrderFlow flow;

    @BeforeEach
    void setUp() {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter)
                .apply(springSecurity()).build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(PRODUCT_ID, 3_000, 5);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
        capture.start();
        rootLogger().addAppender(capture);
    }

    @AfterEach
    void cleanUp() {
        rootLogger().detachAppender(capture);
        capture.stop();
        flow.deleteOrdersOf(MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void oneTraceIdLeadsFromTheOrderToPaidThroughEveryModule() throws Exception {
        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(PRODUCT_ID, 1));
        flow.awaitReservationStatus(order.id(), "CONFIRMED");
        flow.awaitNotifications(order.id(), 1);

        Set<String> lines = capture.messagesOf(order.traceId());
        Set<String> modules = capture.modulesOf(order.traceId());

        assertThat(modules).contains("order", "payment", "stock", "notification", "event");
        assertThat(lines).anyMatch(line -> line.startsWith("order placed"))
                .anyMatch(line -> line.startsWith("payment ready"))
                .anyMatch(line -> line.startsWith("mock PG answered: status=APPROVED"))
                .anyMatch(line -> line.startsWith("stock reserved"))
                .anyMatch(line -> line.startsWith("stock confirmed"))
                .anyMatch(line -> line.startsWith("order paid"))
                .anyMatch(line -> line.startsWith("notification stored"));
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /**
     * Keeps traceId, module and message of every application log line; thread-safe for the listener threads.
     *
     * 애플리케이션 로그 줄마다 traceId, 모듈, 메시지를 보관한다. 리스너 스레드에서도 안전하다.
     */
    private static final class TraceCapture extends AppenderBase<ILoggingEvent> {

        private final Queue<CapturedLine> lines = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            String logger = event.getLoggerName();
            if (logger.startsWith(MODULE_PREFIX)) {
                // The MDC must be read here, on the logging thread.
                // MDC는 로깅 스레드인 여기서 읽어야 한다.
                lines.add(new CapturedLine(event.getMDCPropertyMap().get(MdcKeys.TRACE_ID),
                        logger.substring(MODULE_PREFIX.length()).split("\\.")[0], event.getFormattedMessage()));
            }
        }

        Set<String> modulesOf(String traceId) {
            return lines.stream().filter(line -> traceId.equals(line.traceId())).map(CapturedLine::module)
                    .collect(Collectors.toSet());
        }

        Set<String> messagesOf(String traceId) {
            return lines.stream().filter(line -> traceId.equals(line.traceId())).map(CapturedLine::message)
                    .collect(Collectors.toSet());
        }
    }

    private record CapturedLine(String traceId, String module, String message) {
    }
}
