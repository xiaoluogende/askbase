package com.askbase.system;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import com.askbase.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统接口。
 *
 * <p>{@code /api/health} 只证明 Web 层活着；{@code /api/health/db} 才证明数据库真的连得上。
 *
 * <p>为什么两个都要有：Hikari 连接池是**懒加载**的，没有任何请求碰过数据库时，
 * 「应用启动成功」不代表「数据库连得上」。启动日志里看不到 HikariPool 的初始化记录，
 * 就说明连接池一次都没建过——这时候如果配置错了，你要等到第一个真实请求才会发现。
 */
@RestController
@RequestMapping("/api")
@Tag(name = "02. 系统")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查（不查库）", description = "无需登录")
    // 清掉全局的 security 要求，否则 Swagger UI 上这两个公开接口也会显示小锁，
    // 让人以为要先登录才能点——这是纯粹的视觉误导
    @SecurityRequirements
    public R<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "askbase");
        body.put("status", "UP");
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("time", OffsetDateTime.now().toString());
        return R.ok(body);
    }

    @GetMapping("/health/db")
    @Operation(summary = "数据库健康检查",
            description = "无需登录。会真的查一次库，能发现连接串、账号、字符集问题")
    @SecurityRequirements
    public R<Map<String, Object>> dbHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("version", jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
            body.put("database", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
            body.put("charset", jdbcTemplate.queryForObject(
                    "SELECT @@character_set_database", String.class));
            body.put("tableCount", jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
                    Integer.class));
            body.put("studentCount", jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM student", Integer.class));
            body.put("status", "UP");
        } catch (Exception e) {
            // 健康检查失败必须能直接看出来，不能静默返回 UP
            throw new BizException(ErrorCode.INTERNAL, "数据库不可用：" + e.getMessage());
        }
        return R.ok(body);
    }
}
