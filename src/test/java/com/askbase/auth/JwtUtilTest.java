package com.askbase.auth;

import com.askbase.common.BizException;
import com.askbase.config.AuthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-0123456789";

    private static JwtUtil newJwtUtil(String secret, long minutes) {
        AuthProperties props = new AuthProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setExpireMinutes(minutes);
        return new JwtUtil(props);
    }

    @Test
    void generateAndParseRoundTrip() {
        JwtUtil util = newJwtUtil(SECRET, 60);
        LoginUser user = util.parse(util.generate(42L, "alice"));
        assertThat(user.id()).isEqualTo(42L);
        assertThat(user.username()).isEqualTo("alice");
    }

    @Test
    void shortSecretFailsAtStartup() {
        // 宁可启动失败，也不要等到第一次登录时才抛 WeakKeyException
        assertThatThrownBy(() -> newJwtUtil("too-short", 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtUtil util = newJwtUtil(SECRET, 60);
        String token = util.generate(1L, "bob");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> util.parse(tampered)).isInstanceOf(BizException.class);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtUtil util = newJwtUtil(SECRET, 0);
        String token = util.generate(1L, "carol");
        Thread.sleep(1100); // JWT 的 exp 精度是秒，等一秒再验，避免边界抖动
        assertThatThrownBy(() -> util.parse(token))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
    }
}
