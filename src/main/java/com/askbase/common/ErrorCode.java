package com.askbase.common;

/**
 * 业务错误码。
 *
 * <p>约定：前三位对应 HTTP 语义（4xx / 5xx），后两位是细分。
 * 不直接用 HTTP 状态码，是因为前端需要区分「未登录」和「token 过期」，
 * 而这两个都只能是 401。
 */
public enum ErrorCode {

    OK(0, "ok"),

    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "没有访问权限"),
    NOT_FOUND(40400, "资源不存在"),

    /** SQL 被安全校验拦下（非 SELECT、越权表、危险函数） */
    SQL_REJECTED(42001, "SQL 未通过安全校验"),
    /** SQL 通过了校验但执行失败（语法错、字段不存在等） */
    SQL_EXECUTE_ERROR(42002, "SQL 执行失败"),

    /** 模型调用失败：超时、限流、返回非法结构 */
    LLM_ERROR(43001, "模型调用失败"),

    INTERNAL(50000, "服务器内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
