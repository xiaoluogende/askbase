package com.askbase.query;

import com.askbase.common.BizException;
import com.askbase.ai.ConversationTurn;
import com.askbase.common.ErrorCode;
import com.askbase.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 校验与执行接口。
 *
 * <p>这一层<b>不涉及模型</b>，输入是现成的 SQL。
 * 它的价值有两个：
 * <ol>
 *   <li>先把安全沙箱和只读执行做扎实，接模型时只需要往前面插一段"生成 SQL"</li>
 *   <li>自纠错依赖它——模型生成的 SQL 执行失败时，要靠这里返回的原始错误信息回喂</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/query")
@Tag(name = "04. 查询")
public class QueryController {

    private final SqlValidator validator;
    private final SqlExecutor executor;
    private final QueryService queryService;
    private final ReadOnlyDataSourceProperties properties;
    private final QueryProperties queryProperties;

    public QueryController(SqlValidator validator, SqlExecutor executor,
                           QueryService queryService,
                           ReadOnlyDataSourceProperties properties,
                           QueryProperties queryProperties) {
        this.validator = validator;
        this.executor = executor;
        this.queryService = queryService;
        this.properties = properties;
        this.queryProperties = queryProperties;
    }

    public record SqlRequest(String sql) {
    }

    /**
     * @param history 之前几轮（问题 + SQL）。不传即单轮问答。
     *                服务端会按 askbase.query.max-history-turns 截断，客户端传多少都不会失控。
     */
    public record AskRequest(String question, List<ConversationTurn> history) {
    }

    @PostMapping
    @Operation(summary = "★ 端到端问答",
            description = "自然语言问题 → 召回表 → 模型生成 SQL → 安全校验 → 只读执行 → 返回结果")
    public R<QueryService.AskResult> ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "question 不能为空");
        }
        return R.ok(queryService.ask(request.question(), trimHistory(request.history())));
    }

    /**
     * 只保留最近 N 轮。
     *
     * <p>放在服务端做而不是信任客户端：历史会进 prompt，
     * 客户端传 100 轮就能把 token 成本拉爆，也可能撑爆上下文窗口。
     */
    private List<ConversationTurn> trimHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int max = Math.max(0, queryProperties.getMaxHistoryTurns());
        if (history.size() <= max) {
            return history;
        }
        return history.subList(history.size() - max, history.size());
    }

    @PostMapping("/validate")
    @Operation(summary = "只校验不执行",
            description = "看一条 SQL 会不会被安全沙箱拦下，以及会被自动补上什么 LIMIT")
    public R<Map<String, Object>> validate(@RequestBody SqlRequest request) {
        SqlValidationResult result = validator.validate(request.sql(), properties.getMaxRows());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("originalSql", request.sql());
        body.put("normalizedSql", result.sql());
        body.put("tables", result.tables());
        body.put("violations", result.violations());
        return R.ok(body);
    }

    @PostMapping("/execute")
    @Operation(summary = "校验并执行",
            description = "走完整链路：校验 → 只读连接执行 → 返回结果。非法 SQL 会被直接拒绝")
    public R<Map<String, Object>> execute(@RequestBody SqlRequest request) {
        SqlValidationResult validation = validator.validate(request.sql(), properties.getMaxRows());
        if (!validation.valid()) {
            throw new BizException(ErrorCode.SQL_REJECTED,
                    String.join("；", validation.violations()));
        }

        SqlQueryResult result = executor.execute(validation.sql());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executedSql", validation.sql());
        body.put("tables", validation.tables());
        body.put("columns", result.columns());
        body.put("rows", result.rows());
        body.put("rowCount", result.rowCount());
        body.put("truncated", result.truncated());
        body.put("elapsedMs", result.elapsedMs());
        return R.ok(body);
    }
}
