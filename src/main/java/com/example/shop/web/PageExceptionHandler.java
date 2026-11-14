package com.example.shop.web;

import com.example.shop.common.ShopException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Business errors on page controllers render an error page with the matching status (for example 404 for an unknown
 * product). JSON controllers in web.api are handled first by ApiExceptionHandler.
 *
 * 화면 컨트롤러의 업무 오류는 그에 맞는 상태 코드의 오류 화면을 그린다 (예: 없는 상품이면 404). web.api의 JSON
 * 컨트롤러는 ApiExceptionHandler가 먼저 처리한다.
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
