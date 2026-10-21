package com.askbase.schema.retriever;

import com.askbase.schema.SchemaStore;
import com.askbase.schema.model.SchemaDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合召回：关键词 + 向量。
 *
 * <p>为什么不是二选一：
 * <ul>
 *   <li><b>关键词强在专有名词</b>——用户直接说"student 表"、"monthly_salary"，
 *       字面匹配最准，向量反而可能被语义相近的其他表干扰</li>
 *   <li><b>向量强在同义和口语</b>——"工资"→"月薪"、"多少人"→"COUNT"，
 *       字面完全不重合但语义接近</li>
 * </ul>
 * 两者的失败场景几乎不重叠，融合之后覆盖面明显更宽。
 *
 * <p>加权方式是简单平均（各 0.5）。这是<b>有意的选择</b>：先跑出一个可解释的 baseline，
 * 做消融实验时再调权重——那时候你有测试集，能算出 0.6/0.4 是不是更好。
 * 现在凭感觉调权重，调完也不知道有没有变好。
 */
@Component
@ConditionalOnExpression("'${askbase.ai.embedding.api-key:}' != ''")
public class HybridSchemaRetriever implements SchemaRetriever {

    private static final double KEYWORD_WEIGHT = 0.5;
    private static final double VECTOR_WEIGHT = 0.5;

    /** 关键词分数上限（含表名命中的 +5 加成），用于归一化 */
    private static final double KEYWORD_MAX = 10.0;

    private final KeywordSchemaRetriever keywordRetriever;
    private final VectorSchemaRetriever vectorRetriever;
    private final SchemaStore schemaStore;

    public HybridSchemaRetriever(KeywordSchemaRetriever keywordRetriever,
                                 VectorSchemaRetriever vectorRetriever,
                                 SchemaStore schemaStore) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
        this.schemaStore = schemaStore;
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public List<ScoredTable> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        // 取比 topK 更大的候选集再融合：某张表可能关键词排第 6、向量排第 2，
        // 只看前 topK 会把它漏掉
        int candidateSize = Math.max(topK * 2, 8);

        Map<String, Double> keywordScores = new LinkedHashMap<>();
        for (ScoredTable t : keywordRetriever.retrieve(question, candidateSize)) {
            keywordScores.put(t.table(), t.score());
        }

        Map<String, Double> vectorScores = new LinkedHashMap<>();
        for (ScoredTable t : vectorRetriever.retrieve(question, candidateSize)) {
            vectorScores.put(t.table(), t.score());
        }

        if (keywordScores.isEmpty() && vectorScores.isEmpty()) {
            return List.of();
        }

        // 并集：任一策略召回的都要参与打分，不能只看交集
        java.util.Set<String> allTables = new java.util.LinkedHashSet<>();
        allTables.addAll(keywordScores.keySet());
        allTables.addAll(vectorScores.keySet());

        List<ScoredTable> fused = new ArrayList<>(allTables.size());
        for (String table : allTables) {
            double kw = keywordScores.getOrDefault(table, 0.0);
            double vec = vectorScores.getOrDefault(table, 0.0);

            // 关键词归一到 0~1；向量本身就是 -1~1 的余弦，截到 0~1
            double kwNorm = Math.min(kw / KEYWORD_MAX, 1.0);
            double vecNorm = Math.max(Math.min(vec, 1.0), 0.0);

            double score = 10.0 * (KEYWORD_WEIGHT * kwNorm + VECTOR_WEIGHT * vecNorm);
            fused.add(new ScoredTable(table, round(score),
                    String.format("关键词 %.2f + 向量 %.4f", kw, vec)));
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(ScoredTable::score).reversed()
                        .thenComparing(ScoredTable::table))
                .limit(Math.max(1, topK))
                .toList();
    }

    /** 给 SchemaService 用：reindex 时触发向量重建 */
    public int reindexEmbeddings() {
        List<SchemaDocument> docs = schemaStore.loadAll();
        return vectorRetriever.index(docs);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
