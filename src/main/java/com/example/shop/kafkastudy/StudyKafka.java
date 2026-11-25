package com.example.shop.kafkastudy;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * 학습 단계들이 공유하는 도우미. 쇼핑몰 애플리케이션과는 무관하며 Spring 빈도 아니다.
 * <p>
 * docker-compose의 Kafka는 topic 자동 생성이 꺼져 있으므로(KAFKA_AUTO_CREATE_TOPICS_ENABLE=false)
 * 각 단계는 시작할 때 {@link #createTopic}으로 자기 topic을 만든다.
 */
public final class StudyKafka {

    /** docker compose up -d kafka 로 띄운 브로커. */
    public static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    static {
        // Spring Boot 밖에서 main()으로 실행하면 logback 기본값(DEBUG) 때문에 Kafka 클라이언트 로그가 쏟아진다.
        // 학습용 출력이 묻히지 않도록 WARN 이상만 보이게 한다. (내부 동작이 궁금하면 Level.INFO 로 바꿔 볼 것)
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    private StudyKafka() {
    }

    /** topic이 없으면 만든다. 이미 있으면 그대로 둔다. */
    public static void createTopic(String name, int partitions) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        try (Admin admin = Admin.create(props)) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
            System.out.printf("topic 생성: %s (partitions=%d)%n", name, partitions);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("topic 생성 실패: " + name, e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** key, value 모두 String인 가장 기본 producer 설정. */
    public static Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    public static KafkaProducer<String, String> newProducer() {
        return new KafkaProducer<>(producerProps());
    }

    /**
     * key, value 모두 String인 가장 기본 consumer 설정.
     *
     * @param autoOffsetReset 이 group에 커밋된 offset이 없을 때 어디서부터 읽을지. "earliest"=처음부터, "latest"=지금부터
     */
    public static Properties consumerProps(String groupId, String autoOffsetReset) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    /**
     * 구독하고, partition을 배정받고, "지금 이후" 메시지부터 읽도록 위치를 확정할 때까지 기다린다.
     * 한 번의 실행 안에서 producer와 consumer를 같이 돌리는 단계에서, 이전 실행 때 쌓인 메시지가 섞이지 않게 하려고 쓴다.
     * (consumer는 group id를 실행마다 새로 만들고 auto.offset.reset=latest 로 생성해야 한다)
     */
    public static void subscribeFromNow(KafkaConsumer<String, String> consumer, String topic) {
        consumer.subscribe(List.of(topic));
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(100)); // poll을 해야 group에 합류하고 partition을 배정받는다
        }
        for (TopicPartition partition : consumer.assignment()) {
            consumer.position(partition); // latest 위치를 지금 확정해 둔다
        }
    }

    /**
     * Spring 단계(STEP 6, 7)용. listener container는 백그라운드에서 비동기로 구독하므로, partition을 배정받고
     * 읽기 위치를 확정했을 때 latch를 내려 main 스레드가 그때부터 메시지를 보내게 한다.
     */
    public static ConsumerAwareRebalanceListener readyWhenAssigned(CountDownLatch ready) {
        return new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                partitions.forEach(consumer::position);
                ready.countDown();
            }
        };
    }

    /** 실행마다 다른 group id. 이전 실행이 커밋한 offset의 영향을 받지 않는다. */
    public static String freshGroupId(String prefix) {
        return prefix + "-" + System.currentTimeMillis();
    }

    /** Spring의 Producer/ConsumerFactory 는 Properties 대신 Map 을 받는다. */
    public static Map<String, Object> toMap(Properties props) {
        Map<String, Object> map = new HashMap<>();
        props.forEach((key, value) -> map.put((String) key, value));
        return map;
    }
}
