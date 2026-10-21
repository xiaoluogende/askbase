package com.askbase.query;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全校验。
 *
 * <h3>这是整个项目最重要的一段代码</h3>
 * 因为它的输入是<b>模型生成的、不可信的字符串</b>。NL2SQL 系统最容易出的事故是：
 * 用户问「帮我把重复的学生删掉」，模型老老实实生成一条 {@code DELETE}。
 *
 * <h3>四层校验</h3>
 * <ol>
 *   <li><b>单条语句</b>——禁止 {@code SELECT 1; DROP TABLE student} 这种堆叠</li>
 *   <li><b>必须是 SELECT</b>——用 AST 判断，不是字符串匹配。
 *       在关键字中间插块注释这类手法骗不过去，因为解析器看到的是语法树</li>
 *   <li><b>危险函数黑名单</b>——SLEEP、BENCHMARK、LOAD_FILE、INTO OUTFILE 等</li>
 *   <li><b>自动补 LIMIT</b>——防止一条查询拉回全表把内存打爆</li>
 * </ol>
 *
 * <h3>⚠️ 必须诚实看待这层的强度</h3>
 * 文本黑名单可以被注释、编码、大小写等方式绕过。它<b>不是</b>安全边界，只是"少犯错"。
 * 真正的安全边界是数据库只读账号（{@code askbase_ro}，只有 SELECT 权限）——
 * 就算这里全部漏过，{@code DELETE} 也会被 MySQL 拒绝。
 * <b>永远不要把安全寄托在输入过滤上。</b>
 */
@Component
public class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);

    /** 危险函数 / 子句。用词边界匹配，避免误伤 salary_max 这种列名 */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bsleep\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbenchmark\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bload_file\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binto\\s+outfile\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binto\\s+dumpfile\\b", Pattern.CASE_INSENSITIVE),
            // 系统库：允许模型探查元数据等于把攻击面白送出去
            Pattern.compile("\\binformation_schema\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bperformance_schema\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmysql\\s*\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsys\\s*\\.", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern HAS_LIMIT = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 块注释和行注释。匹配危险函数之前先剥掉——
     * 否则把关键字拆开、中间插一段注释，就能绕过黑名单。
     * （这里不写具体的绕过示例，因为在注释里写正则片段会把注释提前闭合。）
     */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /**
     * @param rawSql  模型生成的原始 SQL
     * @param maxRows 没有 LIMIT 时自动补的行数上限
     */
    public SqlValidationResult validate(String rawSql, int maxRows) {
        List<String> violations = new ArrayList<>();

        if (rawSql == null || rawSql.isBlank()) {
            return SqlValidationResult.reject(rawSql, List.of("SQL 为空"));
        }

        String sql = stripTrailingSemicolon(rawSql.strip());

        // ① 单条语句
        if (containsSemicolonOutsideQuotes(sql)) {
            return SqlValidationResult.reject(rawSql, List.of("只允许单条语句，检测到多条语句用分号分隔"));
        }

        // ② 危险函数（在剥掉注释后的文本上匹配）
        String normalized = stripComments(sql).toLowerCase(Locale.ROOT);
        for (Pattern p : FORBIDDEN) {
            if (p.matcher(normalized).find()) {
                violations.add("包含被禁止的函数或系统库：" + p.pattern());
            }
        }

        // ③ 必须是 SELECT（AST 判断，最可靠的一层）
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return SqlValidationResult.reject(rawSql,
                    List.of("SQL 语法解析失败：" + e.getMessage()));
        }
        if (!(statement instanceof Select)) {
            violations.add("只允许 SELECT 查询，当前语句类型：" + statement.getClass().getSimpleName());
            return SqlValidationResult.reject(rawSql, violations);
        }
        if (!violations.isEmpty()) {
            return SqlValidationResult.reject(rawSql, violations);
        }

        // ④ 提取涉及的表，供后续权限校验使用
        // 用静态方法而不是 new TablesNamesFinder().getTables()：
        // 后者是裸类型（raw type），泛型被擦除后返回 Set，元素是 Object，编译不过。
        Set<String> tables = new LinkedHashSet<>();
        try {
            for (String t : TablesNamesFinder.findTables(sql)) {
                tables.add(t.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            log.warn("提取表名失败：{}", e.getMessage());
        }

        // ⑤ 自动补 LIMIT
        //
        // 为什么补 maxRows + 1 而不是 maxRows：
        // 如果补 maxRows，SQL 本身最多只返回 maxRows 行，执行器就<b>无法区分</b>
        // "总共就这么多行"和"被截断了还有更多"——两种情况都返回 maxRows 行。
        // 多取一行，执行器看到第 maxRows+1 行就知道确实被截断了，然后丢掉它。
        if (!HAS_LIMIT.matcher(normalized).find()) {
            sql = sql + " LIMIT " + (maxRows + 1);
        }

        return SqlValidationResult.ok(sql, List.copyOf(tables));
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * 扫描引号外的分号。
     *
     * <p>不能直接 {@code sql.contains(";")}——字符串字面量里出现分号是合法的
     * （比如 {@code WHERE title = 'a;b'}）。所以要跟踪引号状态。
     */
    private static boolean containsSemicolonOutsideQuotes(String sql) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
            } else if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
            } else {
                switch (c) {
                    case '\'' -> inSingle = true;
                    case '"' -> inDouble = true;
                    case '`' -> inBacktick = true;
                    case ';' -> {
                        return true;
                    }
                    default -> {
                        // 普通字符
                    }
                }
            }
        }
        return false;
    }

    private static String stripComments(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        return LINE_COMMENT.matcher(s).replaceAll(" ");
    }
}
