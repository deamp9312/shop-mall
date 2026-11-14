package com.example.shop.common;

/**
 * Common error body {code, message} (REQ-IF-001 7).
 *
 * 공통 오류 본문 {code, message} (REQ-IF-001 7).
 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.defaultMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
