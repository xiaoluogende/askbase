package com.askbase.auth;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;

/**
 * 当前线程的登录用户上下文。
 *
 * <p>⚠️ 用 ThreadLocal 存用户身份，必须保证请求结束时清理（见 JwtInterceptor#afterCompletion）。
 * 不清的后果：Tomcat 线程池复用，A 用户的身份会泄漏给 B 用户的请求——
 * 这是权限系统里最经典、也最危险的 bug，而且测试环境很难复现。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前用户，未登录直接抛 401 */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
