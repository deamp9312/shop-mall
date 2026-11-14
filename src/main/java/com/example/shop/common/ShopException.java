package com.example.shop.common;

/**
 * Business failure that maps to one {@link ErrorCode}; the web layer turns it into {code, message}.
 *
 * 하나의 {@link ErrorCode}에 대응하는 업무 실패. 웹 계층이 {code, message}로 바꾼다.
 */
public class ShopException extends RuntimeException {

    private final ErrorCode errorCode;

    public ShopException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * @param message shown to the user instead of the default message of the code
     *
     * message: 코드의 기본 메시지 대신 사용자에게 보여 줄 메시지
     */
    public ShopException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
