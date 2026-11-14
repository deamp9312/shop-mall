package com.example.shop.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-IF-002 verification 2): every event class serializes into the full envelope, without personal data.
 *
 * REQ-IF-002 검증 2): 모든 이벤트 클래스가 개인정보 없이 완전한 envelope으로 직렬화된다.
 */
class EventEnvelopeContractTest {

    private static final List<String> ENVELOPE_FIELDS =
            List.of("eventId", "eventType", "version", "occurredAt", "aggregateId", "traceId", "payload");
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 5, 10, 0);

    /**
     * Same date handling as Spring Boot's ObjectMapper (ISO-8601 text, not numeric timestamps).
     *
     * Spring Boot의 ObjectMapper와 같은 날짜 처리 (숫자 타임스탬프가 아닌 ISO-8601 문자열).
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private final List<DomainEvent> events = List.of(
            new OrderCreatedEvent(7L, 1L, List.of(new OrderCreatedEvent.Item(3L, 2, 8_900L)), 17_800L, TIME),
            new OrderCancelledEvent(7L, 1L, "USER_CANCEL"),
            new PaymentCompletedEvent(7L, 11L, 17_800L, TIME),
            new PaymentFailedEvent(7L, 11L),
            new PaymentCancelledEvent(7L, 11L),
            new PaymentCancelRequestedEvent(7L, "ORDER_ALREADY_CANCELLED"));

    @Test
    void everyEventTypeHasAnEventClass() {
        assertThat(events).extracting(DomainEvent::type).containsExactlyInAnyOrder(EventType.values());
        assertThat(events).extracting(event -> event.type().topic()).containsExactlyInAnyOrderElementsOf(EventTopics.ALL);
    }

    @Test
    void serializedEnvelopeHasEveryField() throws Exception {
        for (DomainEvent event : events) {
            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(EventEnvelope.wrap(event, "trace-1")));

            assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);
            assertThat(json.get("eventId").asText()).hasSize(36);
            assertThat(json.get("eventType").asText()).isEqualTo(event.type().name());
            assertThat(json.get("version").asInt()).isEqualTo(1);
            assertThat(Instant.parse(json.get("occurredAt").asText())).isNotNull();
            assertThat(json.get("aggregateId").asLong()).isEqualTo(7L);
            assertThat(json.get("traceId").asText()).isEqualTo("trace-1");
            assertThat(json.get("payload").get("orderId").asLong()).isEqualTo(7L);
            assertThat(json.get("payload").has("type")).isFalse();
            assertThat(json.toString()).doesNotContain("address", "phone", "receiver");
        }
    }

    @Test
    void orderCreatedPayloadMatchesTheContract() throws Exception {
        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(EventEnvelope.wrap(events.get(0), "t")))
                .get("payload");

        assertThat(payload.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("orderId", "memberId", "items", "totalAmount", "expiresAt");
        assertThat(payload.get("items").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("productId", "qty", "price");
    }

    @Test
    void envelopeRoundTripsToTheTypedPayload() throws Exception {
        String json = objectMapper.writeValueAsString(EventEnvelope.wrap(events.get(2), "t"));

        EventEnvelope<PaymentCompletedEvent> read = objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructParametricType(EventEnvelope.class, PaymentCompletedEvent.class));

        assertThat(read.payload()).isEqualTo(events.get(2));
        assertThat(read.hasRequiredFields()).isTrue();
    }
}
