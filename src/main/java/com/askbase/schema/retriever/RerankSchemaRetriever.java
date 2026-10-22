package com.askbase.schema.retriever;

import com.askbase.ai.TableReranker;
import com.askbase.schema.SchemaStore;
import com.askbase.schema.model.SchemaDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回重排：粗排（关键词 + 向量）→ LLM 精排。
 *
 * <h3>为什么不在粗排上调权重</h3>
 * 粗排的两种分数不可比：关键词是覆盖率（0~10），向量是余弦（0~1）。
 * 加权融合出来的分数区间被压得很窄，排序对权重极敏感——
 * 改一点点权重，排序就整体洗牌，而且没法解释为什么某张表该排在前面。
 *
 * <p>重排换了个思路：<b>不再比较分数，而是直接问模型"回答这个问题需要哪几张表"</b>。
 * 这是在做一个语义判断，不是数值比较，所以不受分数尺度的影响。
 *
 * <h3>两阶段的候选集大小</h3>
 * 第一阶段故意召回比 topK 更多的候选（默认全部表），因为粗排本来就会漏——
 * 本项目的实测中，真正需要的表排在第 7 位。给重排留出足够的候选，
 * 它才有机会把对的表捞上来。
 */
@Component
@ConditionalOnExpression("'${askbase.ai.chat.api-key:}' != ''")
public class RerankSchemaRetriever implements SchemaRetriever {

    private static final Logger log = LoggerFactory.getLogger(RerankSchemaRetriever.class);

    /** 第一阶段最多召回几张表当候选。本项目只有 10 张，等于全给 */
    private static final int CANDIDATE_SIZE = 12;

    /** 候选表说明的最大长度——太长的注释会浪费 token，重排只需要判断"这张表是干什么的" */
    private static final int SUMMARY_MAX_CHARS = 70;

    /** 候选说明里最多列几个字段名，太宽的表截断即可 */
    private static final int MAX_COLUMNS = 12;

    private final HybridSchemaRetriever coarseRetriever;
    private final TableReranker reranker;
    private final SchemaStore schemaStore;

    public RerankSchemaRetriever(HybridSchemaRetriever coarseRetriever,
                                 TableReranker reranker,
                                 SchemaStore schemaStore) {
        this.coarseRetriever = coarseRetriever;
        this.reranker = reranker;
        this.schemaStore = schemaStore;
    }

    @Override
    public String name() {
        return "rerank";
    }

    @Override
    public List<ScoredTable> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        // ① 粗排：召回全部候选（不截断），保留原始排名用于日志
        List<ScoredTable> coarse = coarseRetriever.retrieve(question, CANDIDATE_SIZE);
        if (coarse.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> coarseRank = new LinkedHashMap<>();
        for (int i = 0; i < coarse.size(); i++) {
            coarseRank.put(coarse.get(i).table(), i + 1);
        }

        // ② 构造候选（表名 + 一句说明）
        List<TableReranker.Candidate> candidates = new ArrayList<>(coarse.size());
        for (ScoredTable t : coarse) {
            candidates.add(new TableReranker.Candidate(t.table(), summarize(t.table())));
        }

        // ③ 精排
        List<String> ranked = reranker.rerank(question, candidates);

        // ④ 重排失败时回退到粗排——重排是增强，不能成为单点故障
        if (ranked.isEmpty()) {
            log.debug("重排无结果，回退粗排（前 {} 张）", topK);
            return coarse.stream().limit(Math.max(1, topK)).toList();
        }

        List<ScoredTable> result = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size() && result.size() < Math.max(1, topK); i++) {
            String table = ranked.get(i);
            Integer before = coarseRank.get(table);
            result.add(new ScoredTable(
                    table,
                    round(10.0 - i * 1.5),
                    "重排第 " + (i + 1) + " 位（粗排第 " + (before == null ? "-" : before) + "）"));
        }
        return result;
    }

    /**
     * 构造重排用的候选说明：表注释首句 + 字段名。
     *
     * <p>为什么必须带字段名：只给"招聘岗位表"这几个字，重排器根本不知道这张表里有
     * {@code city} 字段，也就判断不出它和"工作地点"有关——实测会漏掉它。
     * 字段名是判断"这张表能不能回答"的关键依据，比注释更有信息量。
     *
     * <p>但也不能把字段注释全带上：10 张表的完整描述有 2700+ token，
     * 重排这一步不值得花这么多。字段名够用。
     */
    private String summarize(String table) {
        SchemaDocument doc = schemaStore.loadAll().stream()
                .filter(d -> d.table().equals(table))
                .findFirst()
                .orElse(null);
        if (doc == null) {
            return table;
        }

        String header = doc.headerOrEmpty();
        // header 的格式是「表名 + 空格 + 表注释」
        String comment = header.length() > table.length()
                ? header.substring(table.length()).strip()
                : "";
        int cut = comment.indexOf('。');
        String firstSentence = cut > 0 ? comment.substring(0, cut) : comment;
        if (firstSentence.length() > SUMMARY_MAX_CHARS) {
            firstSentence = firstSentence.substring(0, SUMMARY_MAX_CHARS) + "…";
        }

        List<String> columns = doc.columnNamesOrEmpty();
        String columnPart = columns.isEmpty()
                ? ""
                : " | 字段: " + String.join(", ", columns.stream().limit(MAX_COLUMNS).toList());

        return (firstSentence.isEmpty() ? table : firstSentence) + columnPart;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
