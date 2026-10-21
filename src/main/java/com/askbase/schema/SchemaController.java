package com.askbase.schema;

import com.askbase.common.ErrorCode;
import com.askbase.common.R;
import com.askbase.schema.model.SchemaDocument;
import com.askbase.schema.retriever.SchemaRetriever;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema 索引与召回接口。
 *
 * <p>索引与召回的核心接口：
 * <ol>
 *   <li>{@code POST /api/schema/reindex} —— 能采集出 10 张表</li>
 *   <li>{@code GET /api/schema/search?q=薪资} —— 召回结果里有 offer 和 job_posting</li>
 *   <li>{@code GET /api/schema/prompt?q=...} —— 能看到将要发给模型的 schema 文本</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/schema")
@Tag(name = "03. Schema")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @PostMapping("/reindex")
    @Operation(summary = "重建 Schema 索引",
            description = "重新扫 information_schema，渲染文档并写入 Redis。改了表结构后调一次")
    public R<SchemaService.IndexResult> reindex() {
        return R.ok(schemaService.reindex());
    }

    @GetMapping("/tables")
    @Operation(summary = "列出已索引的表")
    public R<Map<String, Object>> tables() {
        List<SchemaDocument> docs = schemaService.documents();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meta", schemaService.meta());
        body.put("tables", docs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", d.table());
            m.put("tokenEstimate", d.tokenEstimate());
            return m;
        }).toList());
        return R.ok(body);
    }

    @GetMapping("/tables/{table}")
    @Operation(summary = "查看单张表的完整描述", description = "就是将要喂给模型的那段文本")
    public R<Map<String, Object>> table(@PathVariable String table) {
        SchemaDocument doc = schemaService.document(table);
        if (doc == null) {
            return R.fail(ErrorCode.NOT_FOUND, "表未索引：" + table);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("table", doc.table());
        body.put("tokenEstimate", doc.tokenEstimate());
        body.put("text", doc.text());
        return R.ok(body);
    }

    @GetMapping("/search")
    @Operation(summary = "按自然语言问题召回相关表",
            description = "示例：q=薪资 时，结果里应该有 offer 和 job_posting")
    public R<Map<String, Object>> search(
            @Parameter(description = "自然语言问题", example = "各专业平均薪资是多少") @RequestParam String q,
            @Parameter(description = "召回数量，默认取配置值") @RequestParam(required = false) Integer topK) {

        List<SchemaRetriever.ScoredTable> hits = schemaService.search(q, topK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", q);
        body.put("retriever", schemaService.retrieverName());
        body.put("count", hits.size());
        body.put("results", hits);
        return R.ok(body);
    }

    @GetMapping("/prompt")
    @Operation(summary = "预览将要发给模型的 schema 文本",
            description = "排查准确率问题的第一工具：直接看模型收到了什么")
    public R<SchemaService.PromptPreview> prompt(
            @Parameter(description = "自然语言问题", example = "各专业平均薪资是多少") @RequestParam String q,
            @RequestParam(required = false) Integer topK) {
        return R.ok(schemaService.promptPreview(q, topK));
    }
}
