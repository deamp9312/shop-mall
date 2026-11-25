package com.example.shop.kafkastudy.step7;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * STEP 7. Consumer 오류 처리 — 재시도와 DLT(Dead Letter Topic).
 * <p>
 * 핵심:
 * <ul>
 *   <li>listener가 예외를 던지면 {@link DefaultErrorHandler}가 같은 메시지를 정해진 간격으로 다시 시도한다</li>
 *   <li>끝내 실패하면 {@link DeadLetterPublishingRecoverer}가 메시지를 "원래topic.DLT"로 옮기고 다음 메시지로 넘어간다
 *       (실패한 메시지 하나 때문에 뒤의 메시지가 영원히 막히지 않게)</li>
 *   <li>재시도해도 소용없는 예외(형식 오류 등)는 notRetryable로 지정해 바로 DLT로 보낸다</li>
 * </ul>
 * <pre>
 * [IN]  study.step7 에 3건: "ok-1", "temporary-error", "bad-format"
 * [OUT] 10:00:00 처리 시도: ok-1 → 성공
 *       10:00:00 처리 시도: temporary-error → 실패 (IllegalStateException)
 *       10:00:01 처리 시도: temporary-error → 실패          ← 1초 뒤 재시도 1
 *       10:00:02 처리 시도: temporary-error → 실패          ← 1초 뒤 재시도 2 → 포기
 *       10:00:02 처리 시도: bad-format → 실패 (IllegalArgumentException, 재시도 안 함)
 *       [DLT] value=temporary-error, 원래 topic=study.step7, 원인 예외=java.lang.IllegalStateException
 *       [DLT] value=bad-format, 원래 topic=study.step7, 원인 예외=java.lang.IllegalArgumentException
 * </pre>
 * 쇼핑몰 실제 설정: event/KafkaConfig.java (지수 백오프 1s/2s/4s), event/DeadLetterListener.java (DLT → DB 저장)
 */
public class Step7ErrorHandlingDlt {

    static final String TOPIC = "study.step7";
    static final String DLT = TOPIC + ".DLT"; // DeadLetterPublishingRecoverer 기본 이름 규칙: 원래 topic + ".DLT"

    static final CountDownLatch READY = new CountDownLatch(2); // 원래 topic listener, DLT listener 둘 다

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);
        StudyKafka.createTopic(DLT, 1); // DLT도 원래 topic과 같은 partition 번호로 보내므로 partition 수를 맞춘다
        // Spring Kafka는 재시도마다 스택트레이스를 ERROR로 남긴다. 실무에선 이게 장애 신호지만, 여기선 흐름이 잘 보이게 끈다.
        // (어떻게 찍히는지 보고 싶으면 이 줄을 주석 처리하고 실행해 볼 것)
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.kafka"))
                .setLevel(ch.qos.logback.classic.Level.OFF);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            READY.await(30, TimeUnit.SECONDS);

            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            for (String value : new String[]{"ok-1", "temporary-error", "bad-format"}) {
                template.send(TOPIC, value);
            }
            boolean done = context.getBean(Listeners.class).deadLetters.await(30, TimeUnit.SECONDS);
            System.out.println(done ? "DLT 2건 확인" : "시간 안에 DLT를 다 받지 못함");
        }
    }

    @EnableKafka
    static class Config {

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(StudyKafka.toMap(StudyKafka.producerProps())));
        }

        @Bean
        ConsumerFactory<String, String> consumerFactory() {
            return new DefaultKafkaConsumerFactory<>(
                    StudyKafka.toMap(StudyKafka.consumerProps(StudyKafka.freshGroupId("study-step7"), "latest")));
        }

        /**
         * <pre>
         * [IN]  listener가 예외를 던진 record (topic=study.step7, offset=5, value="temporary-error")
         * [OUT] 1초 간격 2번 재시도 → 그래도 실패하면 study.step7.DLT 로 produce
         *       (헤더 kafka_dlt-original-topic, kafka_dlt-exception-message 등 추가) → offset 커밋, 다음 메시지로
         * </pre>
         */
        @Bean
        DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
            DefaultErrorHandler handler = new DefaultErrorHandler(
                    new DeadLetterPublishingRecoverer(template),
                    new FixedBackOff(1_000L, 2L)); // 1초 간격, 최대 2번 재시도 (총 3번 시도)
            handler.addNotRetryableExceptions(IllegalArgumentException.class); // 다시 해도 똑같이 실패할 예외
            return handler;
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler errorHandler) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.setCommonErrorHandler(errorHandler); // Spring Boot는 DefaultErrorHandler 빈을 자동으로 여기에 연결한다
            factory.getContainerProperties().setConsumerRebalanceListener(StudyKafka.readyWhenAssigned(READY));
            return factory;
        }

        @Bean
        Listeners listeners() {
            return new Listeners();
        }
    }

    static class Listeners {

        final CountDownLatch deadLetters = new CountDownLatch(2);

        /**
         * <pre>
         * [IN]  value="ok-1"            → 정상 리턴 (커밋)
         *       value="temporary-error" → IllegalStateException   (일시 장애 흉내 → 재시도 대상)
         *       value="bad-format"      → IllegalArgumentException (영구 오류 흉내 → 바로 DLT)
         * </pre>
         * try/catch로 예외를 삼키지 말 것. 삼키면 "성공"으로 보고 커밋되어 메시지가 조용히 사라진다.
         */
        @KafkaListener(topics = TOPIC)
        void onMessage(ConsumerRecord<String, String> record) {
            System.out.printf("%s 처리 시도: %s", LocalTime.now().withNano(0), record.value());
            switch (record.value()) {
                case "temporary-error" -> {
                    System.out.println(" → 실패 (IllegalStateException)");
                    throw new IllegalStateException("DB 연결 실패 (일시 장애 흉내)");
                }
                case "bad-format" -> {
                    System.out.println(" → 실패 (IllegalArgumentException, 재시도 안 함)");
                    throw new IllegalArgumentException("메시지 형식이 잘못됨 (영구 오류 흉내)");
                }
                default -> System.out.println(" → 성공");
            }
        }

        /**
         * <pre>
         * [IN]  topic=study.step7.DLT, value="temporary-error" (원문 그대로)
         *       headers: kafka_dlt-original-topic="study.step7",
         *                kafka_dlt-exception-cause-fqcn="java.lang.IllegalStateException",
         *                kafka_dlt-exception-message="Listener method ... threw exception", 스택트레이스 등
         * [OUT] 콘솔 출력 (쇼핑몰은 여기서 dead_letter_events 테이블에 저장해 관리자가 재처리한다)
         * </pre>
         */
        @KafkaListener(topics = DLT, groupId = "study-step7-dlt") // 원래 listener와 다른 group (서로 재조정에 휘말리지 않게)
        void onDeadLetter(ConsumerRecord<String, String> record) {
            System.out.printf("[DLT] value=%s, 원래 topic=%s, 원인 예외=%s%n", record.value(),
                    header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC), header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN));
            deadLetters.countDown();
        }

        private static String header(ConsumerRecord<String, String> record, String name) {
            Header header = record.headers().lastHeader(name);
            return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    }
}
