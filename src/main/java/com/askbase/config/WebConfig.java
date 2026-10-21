package com.askbase.config;

import com.askbase.auth.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册 JWT 拦截器。
 *
 * <p>白名单原则：只放行「不需要身份」的路径。文档相关的路径也必须放行，
 * 否则你连 Knife4j 页面都打不开，会误以为是文档配置写错了。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        // 用 /** 而不是精确匹配：否则新增的 /api/health/db 会被要求登录，
                        // 健康检查要求登录是没意义的（监控系统不会带 token）
                        "/api/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/favicon.ico",
                        "/error"
                );
    }
}
