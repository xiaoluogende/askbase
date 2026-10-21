package com.askbase.auth;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器：解析 Authorization 头，把用户放进 {@link UserContext}。
 *
 * <p>这里抛出的 BizException 会被 GlobalExceptionHandler 接住（Spring 会走 HandlerExceptionResolver），
 * 所以不需要在拦截器里手写 response。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少 Authorization 请求头");
        }
        String token = header.substring(PREFIX.length()).trim();
        UserContext.set(jwtUtil.parse(token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理。线程池复用下不清会导致身份串号，见 UserContext 的注释
        UserContext.clear();
    }
}
