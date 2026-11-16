package com.example.shop.event;

/**
 * 이벤트 하나의 payload이다. 모든 이벤트는 주문에 속한다. {@link #orderId()}는 envelope의 aggregateId이자 Kafka
 * message key이므로 한 주문의 이벤트는 모두 같은 partition에 들어간다 (REQ-IF-002 2). payload에는 주소나 전화번호 같은
 * 개인정보를 담지 않는다.
 */
public interface DomainEvent {

    /** "get" 접두어가 없어 Jackson property가 아니므로 payload에 기록되지 않는다. */
    EventType type();

    long orderId();
}
