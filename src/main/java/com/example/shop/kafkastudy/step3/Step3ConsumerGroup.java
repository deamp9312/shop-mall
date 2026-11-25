package com.example.shop.kafkastudy.step3;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * STEP 3. Consumer Group — "나눠 받기"와 "모두 받기".
 * <p>
 * 핵심:
 * <ul>
 *   <li>같은 group 안의 consumer들은 partition을 <b>나눠</b> 가진다 → 일을 분산 (서버를 늘리면 처리량 증가)</li>
 *   <li>다른 group은 같은 메시지를 <b>각자 전부</b> 받는다 → 쇼핑몰에서 order/stock/notification 모듈이
 *       shop.payment.completed 를 각자 받는 방식</li>
 * </ul>
 * <pre>
 * [IN]  topic=study.step3 (partition 3개), 메시지 6개 (key=k0..k5)
 *       consumer A, B : group "study-step3-work"  (같은 group)
 *       consumer C    : group "study-step3-audit" (다른 group)
 * [OUT] 배정: A=[study.step3-0, study.step3-1]  B=[study.step3-2]   ← 3개 partition을 둘이 나눔
 *       배정: C=[study.step3-0, study.step3-1, study.step3-2]        ← 혼자니까 전부
 *       A가 받은 것: [k0, k2, k3, k5]   B가 받은 것: [k1, k4]         ← 합치면 6개, 겹치지 않음
 *       C가 받은 것: [k0, k1, k2, k3, k4, k5]                          ← 6개 전부
 * </pre>
 * 해볼 것: consumer를 4개로 늘리면? partition은 3개뿐이라 하나는 아무것도 배정받지 못하고 논다.
 */
public class Step3ConsumerGroup {

    private static final String TOPIC = "study.step3";

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 3);
        String workGroup = StudyKafka.freshGroupId("study-step3-work");
        String auditGroup = StudyKafka.freshGroupId("study-step3-audit");

        try (KafkaConsumer<String, String> a = new KafkaConsumer<>(StudyKafka.consumerProps(workGroup, "latest"));
             KafkaConsumer<String, String> b = new KafkaConsumer<>(StudyKafka.consumerProps(workGroup, "latest"));
             KafkaConsumer<String, String> c = new KafkaConsumer<>(StudyKafka.consumerProps(auditGroup, "latest"));
             KafkaProducer<String, String> producer = StudyKafka.newProducer()) {

            a.subscribe(List.of(TOPIC));
            b.subscribe(List.of(TOPIC));
            StudyKafka.subscribeFromNow(c, TOPIC);
            // A, B는 둘 다 poll 해야 group 재조정(rebalance)이 끝난다. 3개 partition이 둘에게 다 나눠질 때까지 번갈아 poll.
            while (a.assignment().isEmpty() || b.assignment().isEmpty()
                    || a.assignment().size() + b.assignment().size() < 3) {
                a.poll(Duration.ofMillis(100));
                b.poll(Duration.ofMillis(100));
            }
            a.assignment().forEach(a::position);
            b.assignment().forEach(b::position);
            System.out.println("배정: A=" + names(a) + "  B=" + names(b));
            System.out.println("배정: C=" + names(c));

            for (int i = 0; i < 6; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "k" + i, "msg-" + i)).get();
            }

            StringBuilder gotA = new StringBuilder();
            StringBuilder gotB = new StringBuilder();
            StringBuilder gotC = new StringBuilder();
            long until = System.currentTimeMillis() + 4_000;
            while (System.currentTimeMillis() < until) {
                a.poll(Duration.ofMillis(200)).forEach(r -> gotA.append(r.key()).append(' '));
                b.poll(Duration.ofMillis(200)).forEach(r -> gotB.append(r.key()).append(' '));
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    gotC.append(r.key()).append(' ');
                }
            }
            System.out.println("A가 받은 것: " + gotA + "  B가 받은 것: " + gotB);
            System.out.println("C가 받은 것: " + gotC);
        }
    }

    private static String names(KafkaConsumer<String, String> consumer) {
        return consumer.assignment().stream().map(TopicPartition::toString).sorted()
                .collect(Collectors.toList()).toString();
    }
}
