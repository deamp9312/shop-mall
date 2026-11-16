package com.example.shop.common;

/** 하나의 {@link ErrorCode}에 대응하는 비즈니스 실패다. 웹 계층이 {code, message}로 변환한다. */
public class ShopException extends RuntimeException {

    private final ErrorCode errorCode;

    public ShopException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /** @param message 코드의 기본 메시지 대신 사용자에게 보여 줄 메시지 */
    public ShopException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
