package com.example.shop.kafkastudy.step1;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;

/**
 * STEP 1-2. 메시지 받기 (Consumer).
 * <p>
 * 핵심: Consumer는 poll()로 메시지를 "당겨 온다"(pull). 그리고 어디까지 읽었는지(offset)를 group 이름으로 Kafka에 기록한다.
 * <pre>
 * [IN]  topic=study.step1 의 메시지들 (Step1Producer가 보낸 것)
 * [OUT] 콘솔 출력
 *       받음: partition=0, offset=0, key=null, value=hello-0
 *       받음: partition=0, offset=1, key=null, value=hello-1
 *       받음: partition=0, offset=2, key=null, value=hello-2
 *       → group "study-step1"의 커밋 offset = 3 (다음에 읽을 위치)
 * </pre>
 * 해볼 것:
 * <ol>
 *   <li>이 Consumer를 한 번 더 실행 → 아무것도 안 나온다 (offset 3부터 읽으므로)</li>
 *   <li>Producer 실행 → Consumer 실행 → 새로 보낸 3개만 나온다</li>
 *   <li>GROUP_ID를 바꾸고 실행 → 처음(offset 0)부터 전부 나온다 (새 group + earliest)</li>
 * </ol>
 */
public class Step1Consumer {

    private static final String GROUP_ID = "study-step1";

    public static void main(String[] args) {
        StudyKafka.createTopic(Step1Producer.TOPIC, 1);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(StudyKafka.consumerProps(GROUP_ID, "earliest"))) {
            consumer.subscribe(List.of(Step1Producer.TOPIC));

            long until = System.currentTimeMillis() + 5_000; // 학습용: 5초만 읽고 종료
            while (System.currentTimeMillis() < until) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("받음: partition=%d, offset=%d, key=%s, value=%s%n",
                            record.partition(), record.offset(), record.key(), record.value());
                }
                // enable.auto.commit=true(기본값)라서 다음 poll() 때 읽은 위치가 자동으로 커밋된다
            }
        } // close() 할 때도 마지막 위치를 커밋한다
        System.out.println("종료");
    }
}
