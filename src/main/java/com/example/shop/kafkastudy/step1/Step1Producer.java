package com.example.shop.kafkastudy.step1;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * STEP 1-1. 메시지 보내기 (Producer).
 * <p>
 * 핵심: Producer는 topic에 (key, value) 쌍을 보낸다. 받는 쪽이 누구인지, 지금 켜져 있는지 전혀 모른다.
 * <pre>
 * [IN]  없음 (main 실행)
 * [OUT] topic=study.step1 에 메시지 3개 저장
 *       key=null, value="hello-0"  → partition=0, offset=0
 *       key=null, value="hello-1"  → partition=0, offset=1
 *       key=null, value="hello-2"  → partition=0, offset=2
 *       (한 번 더 실행하면 offset 3, 4, 5 로 이어서 쌓인다. Kafka는 읽어도 지우지 않는 "로그"다)
 * </pre>
 * 실행 후 {@link Step1Consumer}를 실행해 볼 것.
 */
public class Step1Producer {

    static final String TOPIC = "study.step1";

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);

        try (KafkaProducer<String, String> producer = StudyKafka.newProducer()) {
            for (int i = 0; i < 3; i++) {
                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "hello-" + i);
                // send()는 비동기다. get()을 붙이면 브로커가 저장했다고 응답할 때까지 기다린다.
                RecordMetadata meta = producer.send(record).get();
                System.out.printf("보냄: value=%s → partition=%d, offset=%d%n", record.value(), meta.partition(),
                        meta.offset());
            }
        }
    }
}
