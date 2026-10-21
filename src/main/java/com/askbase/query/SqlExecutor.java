package com.askbase.query;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在只读连接上执行 SQL。
 *
 * <p>三个硬约束，缺一不可：
 * <ol>
 *   <li><b>queryTimeout</b>——模型可能生成不带索引条件的全表扫描，5 秒后 MySQL 主动 kill。
 *       没有这个，一条慢查询能占住连接直到把连接池耗干</li>
 *   <li><b>maxRows</b>——多取一行用于判断是否被截断，而不是默默丢掉数据</li>
 *   <li><b>只读连接</b>——见 {@link ReadOnlyJdbc}</li>
 * </ol>
 */
@Component
public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final ReadOnlyJdbc readOnlyJdbc;
    private final ReadOnlyDataSourceProperties properties;

    public SqlExecutor(ReadOnlyJdbc readOnlyJdbc, ReadOnlyDataSourceProperties properties) {
        this.readOnlyJdbc = readOnlyJdbc;
        this.properties = properties;
    }

    public SqlQueryResult execute(String sql) {
        long start = System.currentTimeMillis();
        int maxRows = properties.getMaxRows();

        try {
            return readOnlyJdbc.jdbc().execute((ConnectionCallback<SqlQueryResult>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
                    // 多取一行：拿到了说明还有更多，前端可以提示"结果被截断"
                    statement.setMaxRows(maxRows + 1);

                    try (ResultSet rs = statement.executeQuery(sql)) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();

                        List<String> columns = new ArrayList<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }

                        List<Map<String, Object>> rows = new ArrayList<>();
                        boolean truncated = false;
                        while (rs.next()) {
                            if (rows.size() >= maxRows) {
                                truncated = true;
                                break;
                            }
                            Map<String, Object> row = new LinkedHashMap<>(columnCount);
                            for (int i = 1; i <= columnCount; i++) {
                                row.put(columns.get(i - 1), normalize(rs.getObject(i)));
                            }
                            rows.add(row);
                        }

                        long elapsed = System.currentTimeMillis() - start;
                        log.debug("SQL 执行完成：{} 行，{} ms，截断={}", rows.size(), elapsed, truncated);
                        return new SqlQueryResult(columns, rows, rows.size(), truncated, elapsed);
                    }
                }
            });
        } catch (Exception e) {
            // 把原始错误信息带出去——自纠错要靠它回喂给模型，
            // 吞掉细节等于自断一条最重要的修复路径
            String message = rootMessage(e);
            log.warn("SQL 执行失败：{} | SQL: {}", message, sql);
            throw new BizException(ErrorCode.SQL_EXECUTE_ERROR, message);
        }
    }

    /**
     * JDBC 会把真实错误包在几层异常里，这里挖到最内层。
     *
     * <p>模型需要看到的是 {@code Unknown column 'xxx' in 'field list'}，
     * 而不是 {@code org.springframework.jdbc.BadSqlGrammarException: ...}。
     */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    /**
     * 把 JDBC 类型转成对 JSON 友好的形式。
     *
     * <p>{@code java.sql.Timestamp} 默认会被 Jackson 序列化成时间戳数字，
     * 前端拿到 1792589566335 根本看不懂。日期统一转字符串。
     */
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return "(binary " + bytes.length + " bytes)";
        }
        return value;
    }
}
