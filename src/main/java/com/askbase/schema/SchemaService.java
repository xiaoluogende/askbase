package com.askbase.schema;

import com.askbase.schema.model.SchemaDocument;
import com.askbase.schema.model.TableSchema;
import com.askbase.schema.retriever.SchemaRetriever;
import com.askbase.schema.retriever.VectorSchemaRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Schema 索引的编排层：采集 → 渲染 → 存储 → 向量化 → 检索。
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final SchemaCollector collector;
    private final SchemaDocumentBuilder builder;
    private final SchemaStore store;
    private final List<SchemaRetriever> retrievers;
    private final ObjectProvider<VectorSchemaRetriever> vectorRetriever;
    private final SchemaProperties properties;

    public SchemaService(SchemaCollector collector,
                         SchemaDocumentBuilder builder,
                         SchemaStore store,
                         List<SchemaRetriever> retrievers,
                         ObjectProvider<VectorSchemaRetriever> vectorRetriever,
                         SchemaProperties properties) {
        this.collector = collector;
        this.builder = builder;
        this.store = store;
        this.retrievers = retrievers;
        this.vectorRetriever = vectorRetriever;
        this.properties = properties;
    }

    /**
     * 按配置选召回策略。
     *
     * <p>用「遍历 List 按 name() 匹配」而不是 {@code @Qualifier}：
     * 后者要求 Bean 名和配置值严格对应，改个类名就悄悄失效；
     * 前者找不到时能明确降级到 keyword，并打日志告诉你配错了。
     */
    private SchemaRetriever active() {
        String wanted = properties.getRetriever();
        for (SchemaRetriever r : retrievers) {
            if (r.name().equalsIgnoreCase(wanted)) {
                return r;
            }
        }
        SchemaRetriever fallback = retrievers.stream()
                .filter(r -> r.name().equals("keyword"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的召回策略"));
        log.warn("配置的召回策略 '{}' 不可用（可能缺 embedding API Key），已降级为 '{}'",
                wanted, fallback.name());
        return fallback;
    }

    public String retrieverName() {
        return active().name();
    }

    /** 当前环境支持哪些召回策略，前端/调试接口用 */
    public List<String> availableRetrievers() {
        return retrievers.stream().map(SchemaRetriever::name).toList();
    }

    /**
     * 重建索引。
     *
     * <p>不做增量更新：schema 变化频率极低，全量重建只要几百毫秒，
     * 增量带来的复杂度完全不值。<b>这是有意识的取舍。</b>
     */
    public IndexResult reindex() {
        long start = System.currentTimeMillis();

        List<TableSchema> tables = collector.collectAll();
        List<SchemaDocument> documents = tables.stream().map(builder::build).toList();
        store.save(documents);

        // 有向量能力就顺带重建向量。失败不影响文本索引——降级为关键词召回仍可用
        int embedded = 0;
        VectorSchemaRetriever vector = vectorRetriever.getIfAvailable();
        if (vector != null) {
            try {
                embedded = vector.index(documents);
            } catch (Exception e) {
                log.warn("向量索引重建失败（文本索引仍可用）：{}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        int totalTokens = documents.stream().mapToInt(SchemaDocument::tokenEstimate).sum();
        log.info("索引重建完成：{} 张表，{} token，向量 {} 条，耗时 {} ms",
                documents.size(), totalTokens, embedded, elapsed);

        return new IndexResult(
                documents.size(),
                totalTokens,
                elapsed,
                OffsetDateTime.now().toString(),
                documents.stream().map(SchemaDocument::table).toList(),
                active().name(),
                embedded);
    }

    public List<SchemaDocument> documents() {
        return store.loadAll();
    }

    public SchemaDocument document(String table) {
        return store.loadAll().stream()
                .filter(d -> d.table().equalsIgnoreCase(table))
                .findFirst()
                .orElse(null);
    }

    public List<SchemaRetriever.ScoredTable> search(String question, Integer topK) {
        int k = topK == null || topK <= 0 ? properties.getTopK() : topK;
        return active().retrieve(question, k);
    }

    /**
     * 拼出将要用在 prompt 里的 schema 片段。
     *
     * <p>NL2SQL 调 prompt 最痛苦的是"看不见发出去的东西"。有这个接口，
     * 排查准确率问题会快一个数量级，也是消融实验的基础工具。
     */
    public PromptPreview promptPreview(String question, Integer topK) {
        List<SchemaRetriever.ScoredTable> hits = search(question, topK);
        List<String> tables = hits.stream().map(SchemaRetriever.ScoredTable::table).toList();

        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (String table : tables) {
            SchemaDocument doc = document(table);
            if (doc != null) {
                sb.append(doc.text()).append('\n');
                tokens += doc.tokenEstimate();
            }
        }
        return new PromptPreview(question, tables, sb.toString(), tokens, active().name());
    }

    public SchemaStore.Meta meta() {
        return store.meta();
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    /** 索引重建结果 */
    public record IndexResult(
            int tableCount,
            int totalTokens,
            long elapsedMs,
            String indexedAt,
            List<String> tables,
            String retriever,
            int embeddedTables
    ) {
    }

    /** prompt 预览 */
    public record PromptPreview(
            String question,
            List<String> recalledTables,
            String schemaText,
            int estimatedTokens,
            String retriever
    ) {
    }
}
