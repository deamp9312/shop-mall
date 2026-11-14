package com.example.shop.web.api;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ErrorResponse;
import com.example.shop.common.ShopException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns errors of JSON controllers into {code, message} with the matching status (REQ-IF-001 7).
 *
 * JSON 컨트롤러의 오류를 그에 맞는 상태 코드와 {code, message}로 바꾼다 (REQ-IF-001 7).
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

    @ExceptionHandler(ShopException.class)
    ResponseEntity<ErrorResponse> handle(ShopException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.status()).body(ErrorResponse.of(errorCode, exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }
}
