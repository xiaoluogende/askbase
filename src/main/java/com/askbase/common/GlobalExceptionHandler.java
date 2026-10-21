package com.askbase.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理。
 *
 * <p>原则：**预期内的错误不打印堆栈，预期外的必须打印堆栈**。
 * 如果所有异常都 log.error 打全栈，线上日志会被参数校验错误淹没，真出问题时反而找不到。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getErrorCode().code(), e.getMessage());
        return R.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleBodyValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + defaultMsg(f))
                .orElse("参数校验失败");
        return R.fail(ErrorCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleParamValidation(ConstraintViolationException e) {
        return R.fail(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public R<Void> handleBadParam(Exception e) {
        return R.fail(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return R.fail(ErrorCode.BAD_REQUEST, "请求体格式错误，需要合法 JSON");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleUnexpected(Exception e) {
        // 兜底：这里必须打全栈，否则线上问题无从查起
        log.error("未捕获异常", e);
        return R.fail(ErrorCode.INTERNAL);
    }

    private static String defaultMsg(FieldError f) {
        return f.getDefaultMessage() == null ? "不合法" : f.getDefaultMessage();
    }
}
