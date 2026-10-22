package com.askbase.query;

import com.askbase.ai.ConversationTurn;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 端到端问答：问题 → 召回 → 生成 → 校验 → 执行（失败则纠错重试）。
 *
 * <h3>自纠错能修什么、不能修什么</h3>
 * 这是设计这一层时最重要的一件事，实测得出的结论：
 *
 * <ul>
 *   <li><b>能修</b>：SQL 语法错、列不存在（数据库报错可回喂）、枚举值写错导致空结果
 *       （0 行是可观测信号）、安全校验不通过（校验器给出原因）</li>
 *   <li><b>不能修</b>：口径错了（比如漏加 {@code sign_status='已签约'}）、
 *       JOIN 条件放在 WHERE 而不是 ON 里——这两类 SQL <b>执行成功、只是结果不对</b>，
 *       没有任何信号可以回喂给模型</li>
 * </ul>
 *
 * <p>所以准确率的提升主要靠业务口径（{@link com.askbase.ai.SemanticProperties}），
 * 自纠错只是兜底。把这两件事分清楚，比堆一个"重试三次"的框架有用得多。
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final SchemaService schemaService;
    private final SqlGenerator sqlGenerator;
    private final SqlValidator validator;
    private final SqlExecutor executor;
    private final ReadOnlyDataSourceProperties properties;
    private final QueryProperties queryProperties;

    public QueryService(SchemaService schemaService,
                        SqlGenerator sqlGenerator,
                        SqlValidator validator,
                        SqlExecutor executor,
                        ReadOnlyDataSourceProperties properties,
                        QueryProperties queryProperties) {
        this.schemaService = schemaService;
        this.sqlGenerator = sqlGenerator;
        this.validator = validator;
        this.executor = executor;
        this.properties = properties;
        this.queryProperties = queryProperties;
    }

    public AskResult ask(String question) {
        return ask(question, List.of());
    }

    /**
     * 端到端问答，支持多轮。
     *
     * <h3>追问的召回问题</h3>
     * "那 2024 届呢" 这种追问几乎没有可检索的词——召回会直接崩掉（分数全是噪声）。
     * 所以<b>检索用的 query 要拼接上一轮的问题</b>：
     * 上一轮问"各专业平均薪资"，这一轮问"那 2024 届呢"，
     * 拼成"各专业平均薪资 那 2024 届呢"再去召回，关键词和向量才有东西可比。
     *
     * <p>但生成时给模型的是原始问题 + 历史，不是拼接后的串——
     * 拼接只是为了召回，不能污染生成意图。
     */
    public AskResult ask(String question, List<ConversationTurn> history) {
        long start = System.currentTimeMillis();

        // ① 召回相关表（追问时用「上一轮问题 + 当前问题」做查询扩展）
        String retrievalQuery = buildRetrievalQuery(question, history);
        List<SchemaRetriever.ScoredTable> hits = schemaService.search(retrievalQuery, null);
        if (hits.isEmpty()) {
            throw new BizException(ErrorCode.SQL_REJECTED,
                    "没有找到与问题相关的表。可能是问题超出了当前数据范围，或者表注释不够清晰导致召回失败");
        }
        List<String> tables = hits.stream().map(SchemaRetriever.ScoredTable::table).toList();
        String schemaText = buildSchemaText(tables);

        // ② 生成 → 校验 → 执行，最多 maxAttempts 轮
        int maxAttempts = Math.max(1, queryProperties.getMaxAttempts());
        List<SqlAttempt> attempts = new ArrayList<>(maxAttempts);
        String feedback = null;

        long modelMs = 0;
        int promptTokens = 0;
        int completionTokens = 0;

        for (int i = 1; i <= maxAttempts; i++) {
            SqlGenerator.GenerationResult generation = sqlGenerator.generate(
                    new SqlGenerator.GenerationInput(question, schemaText, history, feedback));
            modelMs += generation.elapsedMs();
            promptTokens += generation.promptTokens();
            completionTokens += generation.completionTokens();

            SqlSuggestion suggestion = generation.suggestion();

            // ③ 安全校验（不信任模型输出）
            SqlValidationResult validation =
                    validator.validate(suggestion.sql(), properties.getMaxRows());

            if (!validation.valid()) {
                String reason = "SQL 未通过安全校验：" + String.join("；", validation.violations());
                attempts.add(new SqlAttempt(i, suggestion.sql(), null, reason));
                feedback = reason + "。你只能生成单条 SELECT 查询，禁止写操作、系统库和危险函数。";
                log.warn("第 {} 轮校验未通过：{}", i, reason);
                continue;
            }

            // ④ 只读执行
            try {
                SqlQueryResult result = executor.execute(validation.sql());
                attempts.add(new SqlAttempt(i, validation.sql(), result.rowCount(), null));

                boolean empty = result.rowCount() == 0;
                boolean hasNextRound = i < maxAttempts;
                if (empty && hasNextRound && queryProperties.isRetryOnEmptyResult()) {
                    feedback = """
                            上一次查询返回 0 行，可能不是真的没有数据，而是条件写错了。请检查：
                            ① 过滤条件里的枚举值是否用了 schema 中 [取值: ...] 列出的真实取值
                            ② 多表 JOIN 的条件是否正确（尤其 LEFT JOIN 的附加条件应写在 ON 里而不是 WHERE）
                            ③ 时间范围、数值阈值是否过严
                            """;
                    log.info("第 {} 轮返回空结果，进入纠错", i);
                    continue;
                }

                long total = System.currentTimeMillis() - start;
                log.info("问答完成：{} ms（模型 {} ms），{} 轮，召回 {} 张表，返回 {} 行",
                        total, modelMs, attempts.size(), tables.size(), result.rowCount());

                return new AskResult(
                        question,
                        history == null ? 0 : history.size(),
                        schemaService.retrieverName(),
                        tables,
                        validation.sql(),
                        suggestion.explanation(),
                        suggestion.chartType(),
                        result.columns(),
                        result.rows(),
                        result.rowCount(),
                        result.truncated(),
                        attempts,
                        modelMs,
                        total,
                        promptTokens,
                        completionTokens);
            } catch (BizException e) {
                // 数据库原始错误原样回喂，不自己转述
                attempts.add(new SqlAttempt(i, validation.sql(), null, e.getMessage()));
                feedback = "SQL 执行报错：" + e.getMessage();
                log.warn("第 {} 轮执行失败：{}", i, e.getMessage());
            }
        }

        String lastFailure = attempts.isEmpty()
                ? "未知原因"
                : attempts.get(attempts.size() - 1).failure();
        throw new BizException(ErrorCode.SQL_EXECUTE_ERROR,
                "尝试 " + maxAttempts + " 轮仍未成功。最后一次失败原因：" + lastFailure);
    }

    private String buildSchemaText(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String table : tables) {
            SchemaDocument doc = schemaService.document(table);
            if (doc != null) {
                sb.append(doc.text()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 构造检索用的 query。
     *
     * <p>追问往往是省略句（"那 2024 届呢""只看西安的"），单独拿它去召回，
     * 关键词几乎全不命中，向量也只是泛泛的语义相似——结果就是召回一堆无关表。
     * 拼上上一轮的问题，相当于把省略的主语补回来。
     *
     * <p>只拼<b>最近一轮</b>而不是全部历史：更早的轮次容易引入干扰，
     * 而且追问通常紧接上一轮。
     */
    static String buildRetrievalQuery(String question, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        ConversationTurn last = history.get(history.size() - 1);
        if (last.question() == null || last.question().isBlank()) {
            return question;
        }
        return last.question() + " " + question;
    }

    /**
     * @param historyTurns     本次带了几轮历史（0 表示单轮）
     * @param retriever        实际使用的召回策略
     * @param recalledTables   召回的表
     * @param sql              最终执行的 SQL（已补 LIMIT）
     * @param explanation      模型对问题的理解
     * @param chartType        建议图表类型
     * @param attempts         每一轮的尝试记录，长度 > 1 说明触发了纠错
     * @param modelMs          模型累计耗时（含重试）
     * @param totalMs          全链路耗时
     * @param promptTokens     累计输入 token
     * @param completionTokens 累计输出 token
     */
    public record AskResult(
            String question,
            int historyTurns,
            String retriever,
            List<String> recalledTables,
            String sql,
            String explanation,
            String chartType,
            List<String> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            List<SqlAttempt> attempts,
            long modelMs,
            long totalMs,
            int promptTokens,
            int completionTokens
    ) {

        /** 是否靠纠错才成功 */
        public boolean corrected() {
            return attempts != null && attempts.size() > 1;
        }
    }
}
