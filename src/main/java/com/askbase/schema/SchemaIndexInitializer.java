package com.askbase.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时自动建索引。
 *
 * <p>只在索引为空时建，避免每次重启都跑一遍采集（虽然只有几百毫秒，
 * 但重启频繁时也是无谓的等待）。
 *
 * <p><b>失败绝不能阻止应用启动</b>：数据库没起、Redis 没起都是常见的本地开发状态，
 * 这时候应用应该能起来并让你看到错误，而不是直接退出——那样你连日志都看不到。
 * 所以这里全部 catch 掉，只打 WARN。
 */
@Component
public class SchemaIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexInitializer.class);

    private final SchemaService schemaService;
    private final SchemaProperties properties;

    public SchemaIndexInitializer(SchemaService schemaService, SchemaProperties properties) {
        this.schemaService = schemaService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isReindexOnStartup()) {
            log.info("askbase.schema.reindex-on-startup=false，跳过自动建索引");
            return;
        }
        try {
            if (!schemaService.isEmpty()) {
                SchemaStore.Meta meta = schemaService.meta();
                log.info("Schema 索引已存在（{} 张表，建于 {}），跳过自动重建",
                        meta.tableCount(), meta.indexedAt());
                return;
            }
            SchemaService.IndexResult result = schemaService.reindex();
            log.info("Schema 索引初始化完成：{} 张表，约 {} token，耗时 {} ms",
                    result.tableCount(), result.totalTokens(), result.elapsedMs());
        } catch (Exception e) {
            log.warn("Schema 索引初始化失败（不影响应用启动，可稍后调 POST /api/schema/reindex 重试）：{}",
                    e.getMessage());
        }
    }
}
