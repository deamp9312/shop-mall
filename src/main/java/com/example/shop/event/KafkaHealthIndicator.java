package com.example.shop.event;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Adds a "kafka" component to /actuator/health (REQ-F-001); Spring Boot has no built-in Kafka indicator.
 *
 * /actuator/health에 "kafka" 컴포넌트를 추가한다 (REQ-F-001). Spring Boot에는 기본 Kafka 헬스 인디케이터가 없다.
 */
@Component
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final int TIMEOUT_MILLIS = 3_000;

    private final AdminClient adminClient;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka health check failed");
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        var cluster = adminClient.describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MILLIS));
        String clusterId = cluster.clusterId().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        int brokers = cluster.nodes().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).size();
        builder.up().withDetail("clusterId", clusterId).withDetail("brokers", brokers);
    }

    @Override
    public void destroy() {
        adminClient.close();
    }
}
