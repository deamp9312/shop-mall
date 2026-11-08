package com.example.shop.event;

import com.example.shop.common.MdcKeys;
import com.example.shop.common.TraceIds;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Restores traceId (Kafka header) and orderId (message key) into the MDC while a record is handled, so consumer,
 * retry and DLT log lines carry the traceId of the HTTP request that started the flow (REQ-NF-003 1).
 */
@Component
class TraceRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header traceId = record.headers().lastHeader(EventHeaders.TRACE_ID);
        MDC.put(MdcKeys.TRACE_ID, traceId == null
                ? TraceIds.newTraceId()
                : new String(traceId.value(), StandardCharsets.UTF_8));
        if (record.key() != null) {
            MDC.put(MdcKeys.ORDER_ID, record.key().toString());
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(MdcKeys.TRACE_ID);
        MDC.remove(MdcKeys.ORDER_ID);
    }
}
