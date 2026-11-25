package com.example.shop.kafkastudy.step2;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.time.Duration;

/**
 * STEP 2. key와 partition — 순서 보장의 범위.
 * <p>
 * 핵심: topic은 여러 partition으로 나뉜다. 같은 key는 항상 같은 partition으로 가고, 순서는 "partition 안에서만" 보장된다.
 * 쇼핑몰이 key=orderId로 보내는 이유가 이것이다 (한 주문의 생성 → 결제 → 취소 순서 보장).
 * <pre>
 * [IN]  topic=study.step2 (partition 3개)
 *       보내는 순서: (order-1, created) (order-2, created) (order-3, created)
 *                    (order-1, paid)    (order-2, paid)    (order-1, shipped)
 * [OUT] 보냄: key=order-1, value=created → partition=1   ← 같은 key는
 *       보냄: key=order-1, value=paid    → partition=1   ← 항상 같은 partition (hash(key) % 3)
 *       ...
 *       받음: partition=1 key=order-1 value=created
 *       받음: partition=1 key=order-1 value=paid
 *       받음: partition=1 key=order-1 value=shipped        ← order-1 끼리는 보낸 순서 그대로
 *       받음: partition=2 key=order-2 value=created        ← partition 간 순서는 섞일 수 있음
 *       (실제 partition 번호는 key 해시에 따라 다르다)
 * </pre>
 * 해볼 것: key를 null로 바꿔 보내 보기 → 메시지가 여러 partition으로 흩어지고 order-1의 순서가 보장되지 않는다.
 */
public class Step2KeyAndPartition {

    private static final String TOPIC = "study.step2";

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 3);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(StudyKafka.consumerProps(StudyKafka.freshGroupId("study-step2"), "latest"));
             KafkaProducer<String, String> producer = StudyKafka.newProducer()) {
            StudyKafka.subscribeFromNow(consumer, TOPIC);

            String[][] messages = {
                    {"order-1", "created"}, {"order-2", "created"}, {"order-3", "created"},
                    {"order-1", "paid"}, {"order-2", "paid"}, {"order-1", "shipped"}};
            for (String[] m : messages) {
                RecordMetadata meta = producer.send(new ProducerRecord<>(TOPIC, m[0], m[1])).get();
                System.out.printf("보냄: key=%s, value=%-8s → partition=%d, offset=%d%n", m[0], m[1],
                        meta.partition(), meta.offset());
            }

            System.out.println("---- consumer ----");
            int received = 0;
            long until = System.currentTimeMillis() + 5_000;
            while (received < messages.length && System.currentTimeMillis() < until) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    System.out.printf("받음: partition=%d key=%s value=%s%n", r.partition(), r.key(), r.value());
                    received++;
                }
            }
        }
    }
}
