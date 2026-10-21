package com.askbase.auth;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import com.askbase.common.R;
import com.askbase.config.AuthProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>当前：校验 application.yml 里配置的开发用户（明文比对），目的是让登录链路先跑通。
 * 后续改造点（写在这里免得忘）：
 * <ol>
 *   <li>建 sys_user 表，密码存 BCrypt 哈希</li>
 *   <li>注入 UserMapper 查库，用 BCryptPasswordEncoder.matches 比对</li>
 *   <li>登录失败计数 + 锁定（防暴力破解）</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "01. 认证")
public class AuthController {

    private final AuthProperties properties;
    private final JwtUtil jwtUtil;

    public AuthController(AuthProperties properties, JwtUtil jwtUtil) {
        this.properties = properties;
        this.jwtUtil = jwtUtil;
    }

    public record LoginRequest(
            @NotBlank(message = "不能为空") String username,
            @NotBlank(message = "不能为空") String password) {
    }

    @PostMapping("/login")
    @Operation(summary = "登录获取 token",
            description = "校验配置中的开发用户（默认 admin / admin123），后续换成数据库用户")
    public R<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        String expected = properties.getUsers().get(request.username());
        if (expected == null || !expected.equals(request.password())) {
            // 不区分「用户不存在」和「密码错误」，避免账号枚举
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = jwtUtil.generate(1L, request.username());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("expiresInMinutes", properties.getJwt().getExpireMinutes());
        return R.ok(data);
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户")
    public R<LoginUser> me() {
        return R.ok(UserContext.require());
    }
}
