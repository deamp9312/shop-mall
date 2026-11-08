package com.example.shop.common;

/** Business failure that maps to one {@link ErrorCode}; the web layer turns it into {code, message}. */
public class ShopException extends RuntimeException {

    private final ErrorCode errorCode;

    public ShopException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /** @param message shown to the user instead of the default message of the code */
    public ShopException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
