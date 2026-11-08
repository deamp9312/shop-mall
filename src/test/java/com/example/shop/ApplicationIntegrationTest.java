package com.example.shop;

import com.example.shop.common.TraceIdFilter;
import com.example.shop.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-F-001: the application starts on MySQL (Flyway + JPA validate), Redis and Kafka containers. */
class ApplicationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthIsUpIncludingDbRedisAndKafka() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode health = objectMapper.readTree(response.getBody());
        assertThat(health.path("status").asText()).isEqualTo("UP");
        for (String component : new String[] {"db", "redis", "kafka"}) {
            assertThat(health.path("components").path(component).path("status").asText())
                    .as(component).isEqualTo("UP");
        }
    }

    @Test
    void mainPageRendersWithTraceIdHeader() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<h1>shop-mall</h1>");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER)).matches("[0-9a-f]{32}");
    }
}
