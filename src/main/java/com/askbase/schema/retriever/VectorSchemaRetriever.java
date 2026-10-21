package com.askbase.schema.retriever;

import com.askbase.schema.EmbeddingStore;
import com.askbase.schema.SchemaStore;
import com.askbase.schema.model.SchemaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量召回。
 *
 * <p>解决 {@link KeywordSchemaRetriever} 解决不了的问题：<b>同义词</b>。
 * 用户问"工资"，注释里写"月薪"，字面一个词都不重合，关键词检索召回为 0；
 * 但两者的向量非常接近，所以向量检索能召回。
 *
 * <p>实现方式是在 Java 里算余弦相似度，没有用 RediSearch 的 KNN 索引——
 * 只有 10 张表，全量比对是微秒级。理由和取舍写在 {@link com.askbase.schema.EmbeddingStore} 里。
 */
@Component
@ConditionalOnExpression("'${askbase.ai.embedding.api-key:}' != ''")
public class VectorSchemaRetriever implements SchemaRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorSchemaRetriever.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore embeddingStore;
    private final SchemaStore schemaStore;

    public VectorSchemaRetriever(EmbeddingModel embeddingModel,
                                 EmbeddingStore embeddingStore,
                                 SchemaStore schemaStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.schemaStore = schemaStore;
    }

    @Override
    public String name() {
        return "vector";
    }

    /**
     * 给所有表结构文档建向量。
     *
     * <p>嵌入的是 {@code text}（完整描述）而不是 {@code header}：
     * 完整描述里包含字段注释和枚举取值，"月薪""已签约"这些词都在里面，
     * 语义信息最全。只嵌表名的话向量空间太稀疏，同义词问题依然解决不了。
     */
    public int index(List<SchemaDocument> documents) {
        Map<String, float[]> embeddings = new HashMap<>();
        for (SchemaDocument doc : documents) {
            try {
                embeddings.put(doc.table(), embeddingModel.embed(doc.text()));
            } catch (Exception e) {
                // 单张表失败不拖垮整体，下次 reindex 会补上
                log.warn("表 {} 向量化失败：{}", doc.table(), e.getMessage());
            }
        }
        embeddingStore.save(embeddings);
        return embeddings.size();
    }

    @Override
    public List<ScoredTable> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Map<String, float[]> embeddings = embeddingStore.loadAll();
        if (embeddings.isEmpty()) {
            log.debug("向量索引为空，请先调 POST /api/schema/reindex");
            return List.of();
        }

        float[] query;
        try {
            query = embeddingModel.embed(question);
        } catch (Exception e) {
            log.warn("问题向量化失败：{}", e.getMessage());
            return List.of();
        }

        List<ScoredTable> scored = new ArrayList<>(embeddings.size());
        for (Map.Entry<String, float[]> e : embeddings.entrySet()) {
            double similarity = cosine(query, e.getValue());
            scored.add(new ScoredTable(e.getKey(), round(similarity),
                    "余弦相似度 " + round(similarity)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredTable::score).reversed()
                        .thenComparing(ScoredTable::table))
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * 余弦相似度。
     *
     * <p>用余弦而不是欧氏距离：文本向量的模长受文本长度影响，
     * 余弦只看方向，对长短不敏感——这正是我们要的
     * （"薪资"和"平均薪资按专业分组"应该同样接近 offer 表）。
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 供 Hybrid 使用：拿到原始向量分数，便于加权融合 */
    Map<String, Double> rawScores(String question, int topK) {
        Map<String, Double> scores = new HashMap<>();
        for (ScoredTable t : retrieve(question, topK)) {
            scores.put(t.table(), t.score());
        }
        return scores;
    }
}
