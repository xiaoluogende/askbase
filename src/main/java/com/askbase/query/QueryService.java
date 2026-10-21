package com.askbase.query;

import com.askbase.ai.SqlGenerator;
import com.askbase.ai.SqlSuggestion;
import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import com.askbase.schema.SchemaService;
import com.askbase.schema.model.SchemaDocument;
import com.askbase.schema.retriever.SchemaRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 端到端问答：问题 → 召回 → 生成 → 校验 → 执行。
 *
 * <p>五个步骤里，任何一步失败都要给出<b>可读的原因</b>，而不是一个 500：
 * <ol>
 *   <li>召不回表 → 说明问题超出数据范围，应该拒答而不是硬猜</li>
 *   <li>模型生成失败 → 区分"API 挂了"和"模型认为无法回答"</li>
 *   <li>校验不通过 → 把违规原因原样返回，这是模型生成了危险 SQL 的证据</li>
 *   <li>执行失败 → 返回数据库原始错误，后续自纠错要靠它回喂</li>
 * </ol>
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final SchemaService schemaService;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator validator;
    private final SqlExecutor executor;
    private final ReadOnlyDataSourceProperties properties;

    public QueryService(SchemaService schemaService,
                        SqlGenerator sqlGenerator,
                        SqlValidator validator,
                        SqlExecutor executor,
                        ReadOnlyDataSourceProperties properties) {
        this.schemaService = schemaService;
        this.sqlGenerator = sqlGenerator;
        this.validator = validator;
        this.executor = executor;
        this.properties = properties;
    }

    public AskResult ask(String question) {
        long start = System.currentTimeMillis();

        // ① 召回相关表
        List<SchemaRetriever.ScoredTable> hits = schemaService.search(question, null);
        if (hits.isEmpty()) {
            throw new BizException(ErrorCode.SQL_REJECTED,
                    "没有找到与问题相关的表。可能是问题超出了当前数据范围，或者表注释不够清晰导致召回失败");
        }
        List<String> tables = hits.stream().map(SchemaRetriever.ScoredTable::table).toList();

        // ② 拼 schema 文本
        StringBuilder schemaText = new StringBuilder();
        for (String table : tables) {
            SchemaDocument doc = schemaService.document(table);
            if (doc != null) {
                schemaText.append(doc.text()).append('\n');
            }
        }

        // ③ 模型生成
        SqlGenerator.GenerationResult generation =
                sqlGenerator.generate(question, schemaText.toString());
        SqlSuggestion suggestion = generation.suggestion();

        // ④ 安全校验（不信任模型输出）
        SqlValidationResult validation =
                validator.validate(suggestion.sql(), properties.getMaxRows());
        if (!validation.valid()) {
            log.warn("模型生成的 SQL 未通过校验，问题={} SQL={} 原因={}",
                    question, suggestion.sql(), validation.violations());
            throw new BizException(ErrorCode.SQL_REJECTED,
                    "模型生成的 SQL 未通过安全校验：" + String.join("；", validation.violations()));
        }

        // ⑤ 只读执行
        SqlQueryResult result = executor.execute(validation.sql());

        long total = System.currentTimeMillis() - start;
        log.info("问答完成：{} ms（模型 {} ms），召回 {} 张表，返回 {} 行",
                total, generation.elapsedMs(), tables.size(), result.rowCount());

        return new AskResult(
                question,
                schemaService.retrieverName(),
                tables,
                validation.sql(),
                suggestion.explanation(),
                suggestion.chartType(),
                result.columns(),
                result.rows(),
                result.rowCount(),
                result.truncated(),
                generation.elapsedMs(),
                total,
                generation.promptTokens(),
                generation.completionTokens());
    }

    /**
     * @param retriever        实际使用的召回策略
     * @param recalledTables   召回的表
     * @param sql              最终执行的 SQL（已补 LIMIT）
     * @param explanation      模型对问题的理解
     * @param chartType        建议图表类型
     * @param modelMs          模型耗时
     * @param totalMs          全链路耗时
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public record AskResult(
            String question,
            String retriever,
            List<String> recalledTables,
            String sql,
            String explanation,
            String chartType,
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            long modelMs,
            long totalMs,
            int promptTokens,
            int completionTokens
    ) {
    }
}
