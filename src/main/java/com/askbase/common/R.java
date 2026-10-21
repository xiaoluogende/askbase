package com.askbase.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应体。
 *
 * <p>用 record 而不是 class + Lombok：不可变、不用写 getter、Jackson 原生支持。
 * 只有 4 个字段，不值得引入继承体系。
 */
@Schema(description = "统一响应体")
public record R<T>(
        @Schema(description = "业务状态码，0 表示成功", example = "0") int code,
        @Schema(description = "提示信息", example = "ok") String msg,
        @Schema(description = "业务数据") T data,
        @Schema(description = "服务器时间戳（毫秒）") long timestamp
) {

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String msg) {
        return new R<>(errorCode.code(), msg, null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null, System.currentTimeMillis());
    }

    public boolean success() {
        return code == ErrorCode.OK.code();
    }
}
