package com.askbase.auth;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import com.askbase.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析。
 *
 * <p>用 HS256（对称密钥）。选它而不是 RS256 是因为：单服务部署，没有第三方需要验签，
 * 对称密钥少一对密钥的管理成本。如果以后拆成多服务、要对外发 token，再换 RS256。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtUtil(AuthProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // 启动即失败，好过运行时才报 WeakKeyException
            throw new IllegalStateException(
                    "askbase.auth.jwt.secret 至少需要 32 字节（HS256 要求），当前长度: "
                            + (secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length));
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = properties.getJwt().getExpireMinutes();
    }

    public String generate(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(Long.parseLong(claims.getSubject()), claims.get("username", String.class));
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "无效的 token");
        }
    }
}
