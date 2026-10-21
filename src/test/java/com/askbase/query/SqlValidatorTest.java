package com.askbase.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 安全校验的测试。
 *
 * <p>这个类里的每一条用例，都对应一个真实可能发生的事故。
 * 尤其是"模型生成了写操作"那几条——NL2SQL 系统上线后最怕的就是这个。
 */
class SqlValidatorTest {

    private static final int MAX_ROWS = 1000;

    private final SqlValidator validator = new SqlValidator();

    private SqlValidationResult validate(String sql) {
        return validator.validate(sql, MAX_ROWS);
    }

    @Test
    void 普通查询应通过() {
        var r = validate("SELECT name, gpa FROM student WHERE gpa > 3.5");

        assertThat(r.valid()).isTrue();
        assertThat(r.tables()).containsExactly("student");
    }

    @Test
    void 没写LIMIT应自动补上() {
        var r = validate("SELECT * FROM student");

        assertThat(r.valid()).isTrue();
        // 补的是 maxRows+1：多取一行，执行器才能判断"是否被截断"
        assertThat(r.sql()).endsWith("LIMIT " + (MAX_ROWS + 1));
    }

    @Test
    void 已有LIMIT不应重复追加() {
        var r = validate("SELECT * FROM student LIMIT 10");

        assertThat(r.valid()).isTrue();
        assertThat(r.sql()).isEqualTo("SELECT * FROM student LIMIT 10");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM student WHERE id = 1",
            "UPDATE student SET gpa = 4.0",
            "INSERT INTO student (name) VALUES ('x')",
            "DROP TABLE student",
            "TRUNCATE TABLE student",
            "ALTER TABLE student ADD COLUMN x INT",
            "CREATE TABLE evil (id INT)",
            "GRANT ALL ON askbase.* TO 'x'@'%'"
    })
    void 写操作和DDL必须被拒绝(String sql) {
        var r = validate(sql);

        assertThat(r.valid()).as("这条必须被拦下: " + sql).isFalse();
        assertThat(r.violations()).isNotEmpty();
    }

    @Test
    void 堆叠多条语句必须被拒绝() {
        var r = validate("SELECT 1; DROP TABLE student");

        assertThat(r.valid()).isFalse();
        assertThat(r.violations().getFirst()).contains("单条语句");
    }

    @Test
    void 字符串里的分号不算多语句() {
        // 这是 containsSemicolonOutsideQuotes 存在的理由：
        // 直接 sql.contains(";") 会误伤这种完全合法的查询
        var r = validate("SELECT * FROM student WHERE name = 'a;b'");

        assertThat(r.valid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT SLEEP(10)",
            "SELECT * FROM student WHERE SLEEP(5) = 0",
            "SELECT BENCHMARK(1000000, MD5('x'))",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT * FROM student INTO OUTFILE '/tmp/x.csv'"
    })
    void 危险函数必须被拒绝(String sql) {
        var r = validate(sql);

        assertThat(r.valid()).as("这条必须被拦下: " + sql).isFalse();
    }

    @Test
    void 注释绕过也应被拦下() {
        // SLEEP/*x*/(  是常见的黑名单绕过手法。匹配前先剥注释就是为了这个
        var r = validate("SELECT SLEEP/*x*/(10)");

        assertThat(r.valid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM performance_schema.threads",
            "SELECT * FROM mysql.user"
    })
    void 探查系统库必须被拒绝(String sql) {
        var r = validate(sql);

        assertThat(r.valid()).as("不允许模型探查元数据: " + sql).isFalse();
    }

    @Test
    void 应提取出所有涉及的表() {
        var r = validate("""
                SELECT m.name, AVG(o.monthly_salary)
                FROM offer o
                JOIN student s ON o.student_id = s.id
                JOIN major m ON s.major_id = m.id
                GROUP BY m.name
                """);

        assertThat(r.valid()).isTrue();
        assertThat(r.tables()).containsExactlyInAnyOrder("offer", "student", "major");
    }

    @Test
    void 子查询里的表也要提取出来() {
        var r = validate("""
                SELECT * FROM student
                WHERE id IN (SELECT student_id FROM offer WHERE monthly_salary > 15000)
                """);

        assertThat(r.valid()).isTrue();
        assertThat(r.tables()).contains("student", "offer");
    }

    @Test
    void 反引号表名应被规范化() {
        var r = validate("SELECT * FROM `student`");

        assertThat(r.valid()).isTrue();
        assertThat(r.tables()).containsExactly("student");
    }

    @Test
    void CTE应被当作查询放行() {
        var r = validate("""
                WITH high AS (SELECT * FROM offer WHERE monthly_salary > 15000)
                SELECT COUNT(*) FROM high
                """);

        assertThat(r.valid()).isTrue();
    }

    @Test
    void 空SQL应被拒绝() {
        assertThat(validate("").valid()).isFalse();
        assertThat(validate("   ").valid()).isFalse();
        assertThat(validate(null).valid()).isFalse();
    }

    @Test
    void 语法错误应被拒绝并给出原因() {
        var r = validate("SELEC * FORM student");

        assertThat(r.valid()).isFalse();
        assertThat(r.violations().getFirst()).contains("语法解析失败");
    }

    @Test
    void 结尾多余分号应被容忍() {
        var r = validate("SELECT * FROM student;");

        assertThat(r.valid()).isTrue();
        assertThat(r.sql()).doesNotContain(";");
    }
}
