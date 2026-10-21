package com.askbase.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 只读数据源配置。
 *
 * <p>为什么要单独一个数据源，而不是复用应用主库：
 * 应用主库要写 {@code sys_user}、{@code query_log}，必须是读写账号；
 * 而执行模型生成的 SQL 必须用只读账号。两者混用等于把删库权限交给了模型。
 */
@ConfigurationProperties(prefix = "askbase.query.readonly")
public class ReadOnlyDataSourceProperties {

    private String url;
    private String username;
    private String password;

    private int maxPoolSize = 5;
    private int connectionTimeoutMs = 3000;

    /** 单条 SQL 的执行超时（秒）。超时后 MySQL 会主动 kill 查询 */
    private int queryTimeoutSeconds = 5;

    /** 结果集行数上限。超过会截断并标记 truncated */
    private int maxRows = 1000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
}
