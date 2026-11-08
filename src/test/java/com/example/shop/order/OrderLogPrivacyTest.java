package com.example.shop.order;

import com.example.shop.common.TraceIdFilter;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * REQ-SE-003 verification 2): the whole order scenario (order, payment, stock confirm, PAID, notification) logs the
 * order, but never the receiver's address, phone or name.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderLogPrivacyTest extends IntegrationTestSupport {

    private static final long CATEGORY_ID = 9_610L;
    private static final long PRODUCT = 9_611L;
    private static final MemberPrincipal MEMBER =
            new MemberPrincipal(9_611L, "privacy@shop.local", "개인정보", MemberRole.USER, null);

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

    private TestProducts products;
    private OrderFlow flow;

    @BeforeEach
    void setUp() {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter).apply(springSecurity())
                .build();
        products = new TestProducts(jdbc, redis, CATEGORY_ID);
        products.create(PRODUCT, 5_000, 5);
        flow = new OrderFlow(mvc, jdbc, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        flow.deleteOrdersOf(MEMBER.getMemberId());
        products.deleteAll();
    }

    @Test
    void orderScenarioLogsNoShippingInfo(CapturedOutput output) throws Exception {
        PlacedOrder order = flow.placeAndPay(MEMBER, Map.of(PRODUCT, 1));
        flow.awaitReservationStatus(order.id(), "CONFIRMED");
        flow.awaitNotifications(order.id(), 1);

        assertThat(output.getOut()).contains(order.traceId());
        assertThat(output.getAll())
                .doesNotContain(OrderFlow.ADDRESS_LINE)
                .doesNotContain(OrderFlow.RECEIVER_PHONE)
                .doesNotContain(OrderFlow.RECEIVER_NAME);
    }
}
