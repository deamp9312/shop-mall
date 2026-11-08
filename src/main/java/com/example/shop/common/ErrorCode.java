package com.example.shop.common;

import org.springframework.http.HttpStatus;

/** Error codes of the common {code, message} response (REQ-IF-001 7). */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    CART_QUANTITY_LIMIT(HttpStatus.BAD_REQUEST, "상품 1개당 최대 수량을 넘었습니다."),
    CART_ITEM_LIMIT(HttpStatus.BAD_REQUEST, "장바구니에 담을 수 있는 상품 종류를 넘었습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니에 없는 상품입니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND, "DLT 메시지를 찾을 수 없습니다."),
    OUTBOX_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "아웃박스 이벤트를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중지된 상품입니다."),
    STOCK_BELOW_RESERVED(HttpStatus.CONFLICT, "선점 중인 수량보다 적게 재고를 줄일 수 없습니다."),
    STOCK_SYNC_IN_PROGRESS(HttpStatus.CONFLICT, "재고 동기화가 이미 진행 중입니다."),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "재고가 부족한 상품이 있습니다."),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 확인해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "이미 사용된 요청 키입니다. 주문서를 다시 열어 주세요."),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문 상태입니다."),
    PAYMENT_NOT_READY(HttpStatus.CONFLICT, "결제를 준비하고 있습니다. 잠시 후 다시 시도해 주세요."),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 가능 시간이 지났습니다."),
    PAYMENT_NOT_PAYABLE(HttpStatus.CONFLICT, "결제할 수 없는 주문입니다."),
    DEAD_LETTER_ALREADY_RETRIED(HttpStatus.CONFLICT, "이미 재처리한 메시지입니다."),
    DEAD_LETTER_NOT_RETRYABLE(HttpStatus.CONFLICT, "형식이 잘못된 메시지라 재처리할 수 없습니다."),
    OUTBOX_EVENT_NOT_FAILED(HttpStatus.CONFLICT, "발행 실패(FAILED) 상태인 이벤트만 다시 발행할 수 있습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
