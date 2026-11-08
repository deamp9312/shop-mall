package com.example.shop.support;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL, Kafka and Redis containers shared by every integration test in the JVM (REQ-NF-004).
 * Started once and stopped by Testcontainers' Ryuk when the JVM exits. Uses the same images as docker-compose.yml.
 */
public final class TestInfrastructure {

    public static final String DEFAULT_DATABASE = "shop";
    public static final int DEFAULT_REDIS_DATABASE = 0;

    private static final int REDIS_PORT = 6379;
    private static final String MYSQL_USER = "root";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(DEFAULT_DATABASE);
    static final KafkaContainer KAFKA = newKafka();
    /*
     * The seeded context gets its own broker. Both contexts stay cached for the whole run and use the same topic and
     * consumer group names, so with one broker an event published by one context could be consumed by the other.
     */
    static final KafkaContainer SEED_KAFKA = newKafka();
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(REDIS_PORT);

    static {
        Startables.deepStart(MYSQL, KAFKA, SEED_KAFKA, REDIS).join();
    }

    private TestInfrastructure() {
    }

    /** Kafka 3.9 refuses to derive an advertised CONTROLLER listener from 0.0.0.0 (Testcontainers' default bind). */
    private static KafkaContainer newKafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");
    }

    /**
     * Points the application at the shared containers. A test that needs data isolated from the others (for example
     * the seed data test) passes its own MySQL database name, Redis database index and Kafka broker.
     */
    public static void register(DynamicPropertyRegistry registry, String database, int redisDatabase,
                                KafkaContainer kafka) {
        connectionProperties(database, redisDatabase, kafka.getBootstrapServers()).forEach(
                (name, value) -> registry.add(name, () -> value));
    }

    /** The same connection settings as {@link #register}, for an application started in a separate process. */
    public static Map<String, String> connectionProperties(String database, int redisDatabase,
                                                           String kafkaBootstrapServers) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl(database));
        properties.put("spring.datasource.username", MYSQL_USER);
        properties.put("spring.datasource.password", MYSQL.getPassword());
        properties.put("spring.data.redis.host", REDIS.getHost());
        properties.put("spring.data.redis.port", String.valueOf(REDIS.getMappedPort(REDIS_PORT)));
        properties.put("spring.data.redis.password", "");
        properties.put("spring.data.redis.database", String.valueOf(redisDatabase));
        properties.put("spring.kafka.bootstrap-servers", kafkaBootstrapServers);
        return properties;
    }

    /** Plain JDBC access to one database of the shared MySQL container, for tests without an application context. */
    public static DataSource dataSource(String database) {
        return new DriverManagerDataSource(jdbcUrl(database), MYSQL_USER, MYSQL.getPassword());
    }

    /** A broker of its own, started now; the caller stops it. */
    public static KafkaContainer startKafka() {
        KafkaContainer kafka = newKafka();
        kafka.start();
        return kafka;
    }

    /** Bootstrap servers of the broker used by {@link IntegrationTestSupport}. */
    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /** Freezes the broker of {@link IntegrationTestSupport} (docker pause) to simulate a Kafka outage. */
    public static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    public static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static String jdbcUrl(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/"
                + database + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul";
    }
}
