package com.askbase.common;

/**
 * 业务异常。
 *
 * <p>凡是「预期内、需要告诉用户原因」的错误都用它抛，由 GlobalExceptionHandler 统一转成 {@link R}。
 * 不要用 RuntimeException 裸抛——那样堆栈会直接打到前端，既不安全也不专业。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
