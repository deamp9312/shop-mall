package com.example.shop.kafkastudy.step5;

import com.example.shop.kafkastudy.StudyKafka;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * STEP 5. Producer 신뢰성 설정과 전송 실패 처리.
 * <p>
 * 핵심:
 * <ul>
 *   <li>acks=all + enable.idempotence=true : 브로커 복제본이 모두 저장해야 성공, 내부 재시도로 인한 중복 쓰기 방지</li>
 *   <li>send()는 비동기다. 결과는 콜백이나 Future.get()으로 확인해야 한다. 확인하지 않으면 실패를 모른 채 지나간다</li>
 *   <li>실패는 "다시 하면 될 수도 있는 것(RetriableException)"과 "아닌 것"으로 나눠 처리한다
 *       → 쇼핑몰 OutboxRelay.isBrokerUnavailable 이 이 구분을 한다</li>
 * </ul>
 * <pre>
 * [IN]  ① 정상 topic(study.step5)으로 비동기 전송 (콜백)
 *       ② 존재하지 않는 topic(study.no-such-topic)으로 동기 전송 (get)
 * [OUT] ① 콜백 성공: partition=0, offset=12
 *       ② 전송 실패: TimeoutException: Topic study.no-such-topic not present in metadata after 3000 ms.
 *          → 재시도 가능한 오류인가? true   (브로커/메타데이터 문제 → 나중에 다시 보내면 됨)
 * </pre>
 */
public class Step5ProducerReliability {

    private static final String TOPIC = "study.step5";

    public static void main(String[] args) throws Exception {
        StudyKafka.createTopic(TOPIC, 1);

        Properties props = StudyKafka.producerProps();
        props.put(ProducerConfig.ACKS_CONFIG, "all");                    // 모든 복제본 저장 후 성공 응답
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");     // 재시도해도 중복 저장 안 됨
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");           // 메타데이터(브로커/topic 정보) 대기 최대 3초
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");    // 재시도 포함 전송 전체 제한 시간

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // ① 비동기 + 콜백: 실무 기본형. 보내는 스레드는 기다리지 않는다
            CountDownLatch done = new CountDownLatch(1);
            producer.send(new ProducerRecord<>(TOPIC, "order-7", "hello"), (meta, error) -> {
                if (error == null) {
                    System.out.printf("① 콜백 성공: partition=%d, offset=%d%n", meta.partition(), meta.offset());
                } else {
                    System.out.println("① 콜백 실패: " + error);
                }
                done.countDown();
            });
            done.await(10, TimeUnit.SECONDS);

            // ② 동기 전송 실패: 없는 topic (docker-compose Kafka는 topic 자동 생성이 꺼져 있음)
            try {
                producer.send(new ProducerRecord<>("study.no-such-topic", "x")).get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                System.out.println("② 전송 실패: " + cause);
                System.out.println("   → 재시도 가능한 오류인가? " + (cause instanceof RetriableException));
            }
        }
    }
}
