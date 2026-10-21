package com.askbase.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc 生成 OpenAPI，自带 Swagger UI）。
 *
 * <p>访问地址：{@code http://localhost:8080/swagger-ui.html}
 * 调试步骤：先调 POST /api/auth/login 拿 token，再点右上角 Authorize 填入（不用加 Bearer 前缀），
 * 之后所有需要登录的接口都能直接点 Try it out。
 *
 * <p>关于为什么不用 Knife4j，见 pom.xml 里的注释——这是 Boot 3.5 上一个很典型的坑。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearer";

    @Bean
    public OpenAPI askbaseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Askbase API")
                        .version("0.1.0")
                        .description("""
                                自然语言数据分析 Agent。

                                调试步骤：先调 POST /api/auth/login 拿 token，
                                再点右上角 Authorize 填入 token（不需要加 Bearer 前缀），
                                之后所有需要登录的接口都能直接调用。
                                """))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
