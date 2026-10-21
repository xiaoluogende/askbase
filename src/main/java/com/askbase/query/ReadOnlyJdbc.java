package com.askbase.query;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 只读 JDBC 连接池。
 *
 * <p>刻意<b>不</b>声明成 Spring 的 {@code DataSource} Bean——那样会和 Spring Boot 自动配置的
 * 主数据源产生歧义，导致 {@code JdbcTemplate} 注入失败。这里手工建一个 Hikari 池，
 * 自己持有、自己关闭，语义最清晰：<b>这个池只给执行模型生成的 SQL 用，别的地方不要碰。</b>
 *
 * <p>三重只读保障：
 * <ol>
 *   <li>数据库账号 {@code askbase_ro} 只有 SELECT 权限（最强的一层）</li>
 *   <li>连接池 {@code readOnly=true}，驱动层会拒绝写操作</li>
 *   <li>上游 {@link SqlValidator} 已经用 AST 卡死了必须是 SELECT</li>
 * </ol>
 */
@Component
public class ReadOnlyJdbc {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyJdbc.class);

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ReadOnlyJdbc(ReadOnlyDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("askbase-readonly");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaxPoolSize());
        config.setConnectionTimeout(properties.getConnectionTimeoutMs());
        config.setReadOnly(true);

        // -1 = 启动时不尝试建连。数据库没起也要让应用能起来，
        // 否则本地开发时你会连日志都看不到就退出。
        config.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        log.info("只读连接池已初始化：{}", properties.getUrl());
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @PreDestroy
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("只读连接池已关闭");
        }
    }
}
