package com.example.shop.kafkastudy.step4;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * STEP 4. 수동 offset 커밋과 at-least-once — "처리 중 죽으면 어떻게 되나?"
 * <p>
 * 핵심: offset을 커밋하기 전에 consumer가 죽으면, 다음 consumer는 마지막 커밋 위치부터 다시 읽는다.
 * 그래서 같은 메시지가 <b>두 번 처리될 수 있다</b> (at-least-once). 유실은 없지만 중복은 생긴다 → STEP 8에서 해결.
 * 쇼핑몰은 application.yml 의 listener.ack-mode=record 로 "메시지 1개 처리 성공할 때마다 커밋"한다.
 * <pre>
 * [IN]  topic=study.step4 에 msg-0 ~ msg-4 (5개)
 * [OUT] [consumer-1] 처리: msg-0 → 커밋 offset=1
 *       [consumer-1] 처리: msg-1 → 커밋 offset=2
 *       [consumer-1] 처리: msg-2 → 💥 커밋 전에 장애 발생! (close)
 *       [consumer-2] 마지막 커밋 위치부터 다시 시작
 *       [consumer-2] 처리: msg-2 → 커밋 offset=3   ← msg-2가 두 번 처리됨
 *       [consumer-2] 처리: msg-3 → 커밋 offset=4
 *       [consumer-2] 처리: msg-4 → 커밋 offset=5
 * </pre>
 * 해볼 것: "처리 전에" 커밋하도록 순서를 바꿔 보기 → 이번엔 msg-2가 한 번도 처리되지 않는다 (at-most-once, 유실).
 */
public class Step4OffsetCommit {

    private static final String TOPIC = "study.step4";
    private static final int CRASH_AT = 2;

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);
        String group = StudyKafka.freshGroupId("study-step4");

        try (KafkaConsumer<String, String> first = newManualCommitConsumer(group);
             KafkaProducer<String, String> producer = StudyKafka.newProducer()) {
            StudyKafka.subscribeFromNow(first, TOPIC);
            for (int i = 0; i < 5; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "msg-" + i)).get();
            }

            boolean crashed = false;
            long base = -1; // 이번 실행의 첫 메시지 offset (이전 실행 메시지가 topic에 남아 있어도 0,1,2..로 세기 위해)
            while (!crashed) {
                for (ConsumerRecord<String, String> record : first.poll(Duration.ofMillis(500))) {
                    if (base < 0) {
                        base = record.offset();
                    }
                    if (record.offset() - base == CRASH_AT) {
                        System.out.printf("[consumer-1] 처리: %s → 💥 커밋 전에 장애 발생! (close)%n", record.value());
                        crashed = true;
                        break;
                    }
                    process("consumer-1", first, record);
                }
            }
        } // 수동 커밋 모드라 close() 해도 커밋하지 않는다 = 장애로 죽은 것과 같은 효과

        System.out.println("[consumer-2] 마지막 커밋 위치부터 다시 시작");
        try (KafkaConsumer<String, String> second = newManualCommitConsumer(group)) {
            second.subscribe(List.of(TOPIC));
            int processed = 0;
            long until = System.currentTimeMillis() + 10_000;
            while (processed < 5 - CRASH_AT && System.currentTimeMillis() < until) {
                for (ConsumerRecord<String, String> record : second.poll(Duration.ofMillis(500))) {
                    process("consumer-2", second, record);
                    processed++;
                }
            }
        }
    }

    /** 비즈니스 처리 → 성공하면 "다음에 읽을 위치(offset+1)"를 커밋. */
    private static void process(String who, KafkaConsumer<String, String> consumer,
                                ConsumerRecord<String, String> record) {
        // ... 여기서 DB 저장 같은 실제 처리를 한다 ...
        long next = record.offset() + 1;
        consumer.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(next)));
        System.out.printf("[%s] 처리: %s → 커밋 offset=%d%n", who, record.value(), next);
    }

    private static KafkaConsumer<String, String> newManualCommitConsumer(String group) {
        Properties props = StudyKafka.consumerProps(group, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // 커밋은 내가 직접 한다
        return new KafkaConsumer<>(props);
    }
}
