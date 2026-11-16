package com.example.shop.event;

import java.time.LocalDateTime;
import java.util.List;

/** order -> payment. 가격은 주문에 복사해 둔 서버 측 가격이다 (REQ-IF-002 4). */
public record OrderCreatedEvent(long orderId, long memberId, List<Item> items, long totalAmount,
                                LocalDateTime expiresAt) implements DomainEvent {

    public record Item(long productId, int qty, long price) {
    }

    @Override
    public EventType type() {
        return EventType.ORDER_CREATED;
    }
}
