package com.askbase.auth;

/**
 * 当前请求的登录用户。
 *
 * @param id       用户 ID
 * @param username 登录名
 */
public record LoginUser(Long id, String username) {
}
