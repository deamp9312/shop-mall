package com.example.shop.kafkastudy.step6;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * STEP 6. Spring Kafka — KafkaTemplate 과 {@code @KafkaListener}.
 * <p>
 * 핵심: STEP 1~5에서 직접 만들던 producer / poll 루프 / 커밋을 Spring이 대신 해 준다.
 * <ul>
 *   <li>보내기: {@code kafkaTemplate.send(topic, key, value)}</li>
 *   <li>받기: 메서드에 {@code @KafkaListener}만 붙이면 Spring이 백그라운드 스레드에서 poll 하고, 메서드가 정상 리턴하면 커밋한다</li>
 * </ul>
 * 쇼핑몰에서는 Spring Boot가 아래 {@link Config}의 빈들을 application.yml 설정으로 자동 생성한다.
 * 여기서는 Boot가 숨겨 둔 것을 보이려고 직접 만든다. (그래서 {@code @Configuration}도 붙이지 않았다 —
 * 쇼핑몰 앱의 컴포넌트 스캔에 잡히지 않게 하려는 것)
 * <pre>
 * [IN]  kafkaTemplate.send("study.step6", "order-1", "주문 생성") 등 3건
 * [OUT] [listener 스레드: org.springframework.kafka.KafkaListenerEndpointContainer#0-0-C-1]
 *       받음: key=order-1, value=주문 생성, partition=0, offset=0
 *       받음: key=order-1, value=결제 완료, partition=0, offset=1
 *       받음: key=order-1, value=배송 시작, partition=0, offset=2
 * </pre>
 * 비교해 볼 실제 코드: event/OutboxRelay.java (send), order/OrderEventListener.java (@KafkaListener)
 */
public class Step6SpringKafka {

    static final String TOPIC = "study.step6";

    /** 학습용 동기화: listener가 partition을 배정받은 뒤에 메시지를 보내기 위해. */
    static final CountDownLatch READY = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            READY.await(30, TimeUnit.SECONDS);

            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            for (String step : new String[]{"주문 생성", "결제 완료", "배송 시작"}) {
                template.send(TOPIC, "order-1", step);
            }
            boolean all = context.getBean(Listener.class).received.await(15, TimeUnit.SECONDS);
            System.out.println(all ? "3건 모두 받음" : "시간 안에 다 받지 못함");
        }
    }

    /** Spring Boot가 자동으로 만들어 주는 것들을 손으로 만든 버전. */
    @EnableKafka // @KafkaListener 를 찾아 listener container 를 띄우게 한다
    static class Config {

        @Bean
        ProducerFactory<String, String> producerFactory() {
            return new DefaultKafkaProducerFactory<>(StudyKafka.toMap(StudyKafka.producerProps()));
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }

        @Bean
        ConsumerFactory<String, String> consumerFactory() {
            // 이 실행에서 보낸 메시지만 받도록 실행마다 새 group + latest
            return new DefaultKafkaConsumerFactory<>(
                    StudyKafka.toMap(StudyKafka.consumerProps(StudyKafka.freshGroupId("study-step6"), "latest")));
        }

        /** {@code @KafkaListener}가 기본으로 찾는 빈 이름이 "kafkaListenerContainerFactory" 이다. */
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                ConsumerFactory<String, String> consumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            factory.getContainerProperties().setConsumerRebalanceListener(StudyKafka.readyWhenAssigned(READY));
            return factory;
        }

        @Bean
        Listener listener() {
            return new Listener();
        }
    }

    static class Listener {

        final CountDownLatch received = new CountDownLatch(3);

        /**
         * <pre>
         * [IN]  ConsumerRecord(topic=study.step6, key="order-1", value="주문 생성", partition=0, offset=0)
         * [OUT] 콘솔 출력. 정상 리턴 → Spring이 offset 커밋
         * </pre>
         */
        @KafkaListener(topics = TOPIC)
        void onMessage(ConsumerRecord<String, String> record) {
            System.out.printf("[listener 스레드: %s]%n받음: key=%s, value=%s, partition=%d, offset=%d%n",
                    Thread.currentThread().getName(), record.key(), record.value(), record.partition(),
                    record.offset());
            received.countDown();
        }
    }
}
