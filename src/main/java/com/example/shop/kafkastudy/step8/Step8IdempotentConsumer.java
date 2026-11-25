package com.example.shop.kafkastudy.step8;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * STEP 8. 멱등 Consumer — 중복 메시지를 한 번만 처리하기.
 * <p>
 * 핵심: STEP 4(커밋 전 장애), STEP 7(재시도), 그리고 producer 재전송 때문에 같은 메시지는 두 번 이상 올 수 있다.
 * Kafka만으로 "정확히 한 번"을 기대하지 말고, 메시지마다 고유한 eventId를 붙여 consumer가 이미 처리한 것은 건너뛴다.
 * <pre>
 * [IN]  study.step8 에 4건 (헤더 eventId 포함)
 *       eventId=e-1 "주문 7 결제 완료"
 *       eventId=e-2 "주문 8 결제 완료"
 *       eventId=e-1 "주문 7 결제 완료"   ← 재전송된 중복
 *       eventId=e-3 "주문 9 결제 완료"
 * [OUT] 처리:      eventId=e-1 → 주문 7 결제 완료  (포인트 적립 +100)
 *       처리:      eventId=e-2 → 주문 8 결제 완료  (포인트 적립 +100)
 *       중복 skip: eventId=e-1                      ← 두 번 적립되지 않음
 *       처리:      eventId=e-3 → 주문 9 결제 완료  (포인트 적립 +100)
 *       총 적립 포인트 = 300 (중복 처리했다면 400)
 * </pre>
 * 여기서는 이해를 위해 메모리 Set을 쓴다. 재시작하면 Set이 사라지므로 실무에선 안 된다.
 * 쇼핑몰은 DB 테이블 processed_events(consumer_group, event_id) 의 PK로 중복을 막고, 비즈니스 처리와
 * 같은 트랜잭션으로 저장한다 → event/IdempotentEventProcessor.java
 */
public class Step8IdempotentConsumer {

    private static final String TOPIC = "study.step8";

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(StudyKafka.consumerProps(StudyKafka.freshGroupId("study-step8"), "latest"));
             KafkaProducer<String, String> producer = StudyKafka.newProducer()) {
            StudyKafka.subscribeFromNow(consumer, TOPIC);

            String[][] events = {
                    {"e-1", "주문 7 결제 완료"}, {"e-2", "주문 8 결제 완료"},
                    {"e-1", "주문 7 결제 완료"}, {"e-3", "주문 9 결제 완료"}};
            for (String[] e : events) {
                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, e[1]);
                record.headers().add("eventId", e[0].getBytes(StandardCharsets.UTF_8));
                producer.send(record).get();
            }

            Set<String> processed = new HashSet<>(); // 실무: DB 테이블 (processed_events)
            int points = 0;
            int received = 0;
            long until = System.currentTimeMillis() + 5_000;
            while (received < events.length && System.currentTimeMillis() < until) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    received++;
                    String eventId = header(record, "eventId");
                    if (!processed.add(eventId)) { // add가 false = 이미 있음
                        System.out.printf("중복 skip: eventId=%s%n", eventId);
                        continue;
                    }
                    points += 100; // 비즈니스 처리 (두 번 실행되면 안 되는 일)
                    System.out.printf("처리:      eventId=%s → %s  (포인트 적립 +100)%n", eventId, record.value());
                }
            }
            System.out.printf("총 적립 포인트 = %d (중복 처리했다면 %d)%n", points, events.length * 100);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
