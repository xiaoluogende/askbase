package com.askbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证相关配置。
 *
 * <p>当前用配置里的开发用户（{@code askbase.auth.users}），后续换成 sys_user 表 + BCrypt。
 * 这样不用等数据库建好就能把登录链路跑通。
 */
@ConfigurationProperties(prefix = "askbase.auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();

    /** 开发用户：username -> 明文密码。仅 dev 环境使用 */
    private Map<String, String> users = new HashMap<>();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Map<String, String> getUsers() {
        return users;
    }

    public void setUsers(Map<String, String> users) {
        this.users = users;
    }

    public static class Jwt {

        /** HS256 要求密钥不短于 32 字节，否则启动即报错 */
        private String secret;

        private long expireMinutes = 720;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpireMinutes() {
            return expireMinutes;
        }

        public void setExpireMinutes(long expireMinutes) {
            this.expireMinutes = expireMinutes;
        }
    }
}
