package com.askbase.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema 采集与检索的配置。
 */
@ConfigurationProperties(prefix = "askbase.schema")
public class SchemaProperties {

    /**
     * 召回策略：keyword / vector / hybrid。
     *
     * <p>配了 vector 或 hybrid 但没配 embedding Key 时，会自动降级为 keyword
     * 并打一条 WARN——本地开发不应该因为缺 Key 就跑不起来。
     */
    private String retriever = "keyword";

    /** 检索时召回多少张表。给多了是干扰项，给少了 JOIN 不出来 */
    private int topK = 4;

    /** 去重后取值数 ≤ 此值的字符串列，视为枚举列并采样取值 */
    private int enumMaxDistinct = 20;

    /** 枚举列最多采样几个取值 */
    private int enumSampleLimit = 8;

    /** 每张表采几行样例数据 */
    private int sampleRowLimit = 2;

    /**
     * 不参与检索的表。应用自己的表（用户、权限、日志）必须排除——
     * 否则模型可能生成 SQL 去查 sys_user，把密码哈希当业务数据返回。
     */
    private List<String> excludedTables = new ArrayList<>(List.of(
            "sys_user", "sys_table_permission", "query_log"));

    /** 是否在启动时自动重建索引 */
    private boolean reindexOnStartup = true;

    public String getRetriever() {
        return retriever;
    }

    public void setRetriever(String retriever) {
        this.retriever = retriever;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getEnumMaxDistinct() {
        return enumMaxDistinct;
    }

    public void setEnumMaxDistinct(int enumMaxDistinct) {
        this.enumMaxDistinct = enumMaxDistinct;
    }

    public int getEnumSampleLimit() {
        return enumSampleLimit;
    }

    public void setEnumSampleLimit(int enumSampleLimit) {
        this.enumSampleLimit = enumSampleLimit;
    }

    public int getSampleRowLimit() {
        return sampleRowLimit;
    }

    public void setSampleRowLimit(int sampleRowLimit) {
        this.sampleRowLimit = sampleRowLimit;
    }

    public List<String> getExcludedTables() {
        return excludedTables;
    }

    public void setExcludedTables(List<String> excludedTables) {
        this.excludedTables = excludedTables;
    }

    public boolean isReindexOnStartup() {
        return reindexOnStartup;
    }

    public void setReindexOnStartup(boolean reindexOnStartup) {
        this.reindexOnStartup = reindexOnStartup;
    }

    public boolean isExcluded(String table) {
        return excludedTables.stream().anyMatch(t -> t.equalsIgnoreCase(table));
    }
}
