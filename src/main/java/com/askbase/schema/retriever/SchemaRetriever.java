package com.askbase.schema.retriever;

import java.util.List;

/**
 * 表召回接口。
 *
 * <p>抽象成接口而不是直接写一个方法，是为了能做<b>消融实验</b>：
 * 同一批测试问题，分别用 keyword / vector / hybrid 跑，比较召回率和最终准确率。
 * 没有 baseline 的优化都是自嗨——这句话在做评估时会反复应验。
 *
 * <p>实现通过配置 {@code askbase.schema.retriever} 切换。
 */
public interface SchemaRetriever {

    /** 实现名，用于日志和接口返回，便于确认当前用的是哪个策略 */
    String name();

    /**
     * 根据自然语言问题召回相关的表。
     *
     * @param question 用户问题
     * @param topK     最多返回几张表
     * @return 按相关度降序；可能为空（说明问题和任何表都对不上）
     */
    List<ScoredTable> retrieve(String question, int topK);

    /**
     * 召回结果。
     *
     * @param table  表名
     * @param score  相关度分数，不同实现的量纲不同，只用于同一实现内部排序
     * @param reason 命中原因（命中了哪些词）。这是给调试和演示用的——
     *               面试时能打开接口指着说"这张表是因为'薪资'这个词被召回的"
     */
    record ScoredTable(String table, double score, String reason) {
    }
}
