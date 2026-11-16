package com.example.shop.web;

import com.example.shop.common.ShopException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * 페이지 컨트롤러의 비즈니스 오류는 해당 상태 코드의 에러 페이지로 렌더링한다(예: 존재하지 않는 상품이면 404).
 * web.api의 JSON 컨트롤러는 ApiExceptionHandler가 먼저 처리한다.
 */
@ControllerAdvice(basePackageClasses = PageExceptionHandler.class)
class PageExceptionHandler {

    @ExceptionHandler(ShopException.class)
    ModelAndView handle(ShopException exception, HttpServletResponse response) {
        int status = exception.getErrorCode().status().value();
        response.setStatus(status);
        ModelAndView page = new ModelAndView(status == HttpServletResponse.SC_NOT_FOUND ? "error/404" : "error/error");
        page.addObject("status", status);
        page.addObject("message", exception.getMessage());
        return page;
    }
}
