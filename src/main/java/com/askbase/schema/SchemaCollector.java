package com.askbase.schema;

import com.askbase.schema.model.ColumnSchema;
import com.askbase.schema.model.ForeignKeySchema;
import com.askbase.schema.model.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从 {@code information_schema} 采集表结构。
 *
 * <p>采集的不只是"表名 + 字段名"，还包括三样东西，它们直接决定 LLM 能不能写对 SQL：
 * <ol>
 *   <li><b>注释</b>——用户说"薪资"，字段叫 {@code monthly_salary}，只有注释能桥接这个语义鸿沟</li>
 *   <li><b>枚举取值</b>——LLM 不知道 {@code sign_status} 只能是「已签约/未签约/已违约」，
 *       就会编出 {@code WHERE sign_status = 'success'}。采样取值能根治这类幻觉</li>
 *   <li><b>外键关系</b>——JOIN 写错是多表查询最大的错误来源，必须显式给出</li>
 * </ol>
 */
@Component
public class SchemaCollector {

    private static final Logger log = LoggerFactory.getLogger(SchemaCollector.class);

    /**
     * 标识符白名单校验。
     *
     * <p>表名和字段名来自 information_schema，理论上安全；但它们会被拼进动态 SQL
     * （{@code COUNT(*)}、{@code SELECT DISTINCT}），所以仍然校验一遍。
     * 这类"看起来不会出事"的地方恰恰是注入的温床——一旦将来有人把表名改成可配置的，
     * 这里就成了入口。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbc;
    private final SchemaProperties properties;

    public SchemaCollector(JdbcTemplate jdbc, SchemaProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public List<TableSchema> collectAll() {
        List<String> tables = listTables();
        List<TableSchema> result = new ArrayList<>(tables.size());
        for (String table : tables) {
            try {
                result.add(describe(table));
            } catch (Exception e) {
                // 单张表失败不能拖垮整个索引——线上总会有权限不足或损坏的表
                log.warn("采集表 {} 失败，跳过：{}", table, e.getMessage());
            }
        }
        log.info("Schema 采集完成，共 {} 张表", result.size());
        return result;
    }

    private List<String> listTables() {
        return jdbc.queryForList("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """, String.class)
                .stream()
                .filter(t -> !properties.isExcluded(t))
                .toList();
    }

    private TableSchema describe(String table) {
        requireSafe(table);

        String comment = jdbc.queryForObject("""
                SELECT COALESCE(table_comment, '')
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);

        List<ColumnSchema> columns = collectColumns(table);
        List<ForeignKeySchema> foreignKeys = collectForeignKeys(table);
        long rowCount = countRows(table);
        List<String> samples = sampleRows(table, columns);

        return new TableSchema(table, comment, rowCount, columns, foreignKeys, samples);
    }

    private List<ColumnSchema> collectColumns(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name, column_type, is_nullable, column_key, COALESCE(column_comment, '') AS column_comment
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """, table);

        List<ColumnSchema> columns = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String name = str(row.get("column_name"));
            String type = str(row.get("column_type")).toLowerCase();
            boolean nullable = "YES".equalsIgnoreCase(str(row.get("is_nullable")));
            boolean pk = "PRI".equalsIgnoreCase(str(row.get("column_key")));
            String comment = str(row.get("column_comment"));

            // 只对字符串类型的低基数列采样取值。数值列（如 gpa）不是枚举，
            // 硬采样反而会把 3.64、3.71 这种值塞进 prompt，纯属浪费 token。
            List<String> enumValues = isStringType(type) ? sampleEnumValues(table, name) : List.of();

            columns.add(new ColumnSchema(name, type, nullable, pk, comment, enumValues));
        }
        return columns;
    }

    private List<ForeignKeySchema> collectForeignKeys(String table) {
        return jdbc.query("""
                        SELECT column_name, referenced_table_name, referenced_column_name
                        FROM information_schema.key_column_usage
                        WHERE table_schema = DATABASE()
                          AND table_name = ?
                          AND referenced_table_name IS NOT NULL
                        ORDER BY ordinal_position
                        """,
                (rs, i) -> new ForeignKeySchema(
                        rs.getString("column_name"),
                        rs.getString("referenced_table_name"),
                        rs.getString("referenced_column_name")),
                table);
    }

    /**
     * 低基数列采样。
     *
     * <p>先用 COUNT(DISTINCT) 判断基数，再决定要不要取值——直接 SELECT DISTINCT 全量拉，
     * 在高基数列（如 name、student_no）上会拉回几万行，白烧内存。
     */
    private List<String> sampleEnumValues(String table, String column) {
        try {
            Integer distinct = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT `" + column + "`) FROM `" + table + "`", Integer.class);
            if (distinct == null || distinct == 0 || distinct > properties.getEnumMaxDistinct()) {
                return List.of();
            }
            return jdbc.queryForList(
                    "SELECT DISTINCT `" + column + "` FROM `" + table
                            + "` WHERE `" + column + "` IS NOT NULL AND `" + column + "` <> ''"
                            + " ORDER BY 1 LIMIT " + properties.getEnumSampleLimit(),
                    String.class);
        } catch (Exception e) {
            // 采样失败不影响主流程，只是少了个提示
            log.debug("字段 {}.{} 枚举采样失败：{}", table, column, e.getMessage());
            return List.of();
        }
    }

    private long countRows(String table) {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<String> sampleRows(String table, List<ColumnSchema> columns) {
        int limit = properties.getSampleRowLimit();
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `" + table + "` LIMIT " + limit);
            List<String> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                StringBuilder sb = new StringBuilder();
                for (ColumnSchema c : columns) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    Object v = row.get(c.name());
                    sb.append(c.name()).append('=').append(v == null ? "NULL" : v);
                }
                out.add(sb.toString());
            }
            return out;
        } catch (Exception e) {
            log.debug("表 {} 样例数据采集失败：{}", table, e.getMessage());
            return List.of();
        }
    }

    private static boolean isStringType(String type) {
        return type.startsWith("varchar") || type.startsWith("char")
                || type.startsWith("enum") || type.startsWith("text");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void requireSafe(String identifier) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("非法标识符：" + identifier);
        }
    }
}
