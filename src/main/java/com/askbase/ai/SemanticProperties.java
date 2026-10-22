package com.askbase.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务口径（语义层）。
 *
 * <h3>为什么需要这一层</h3>
 * 表结构和字段注释能告诉模型"有哪些数据"，但告诉不了它"怎么算才算对"。
 * 比如：
 * <ul>
 *   <li>「平均薪资」是指所有 offer 的月薪均值，还是只算已签约的？</li>
 *   <li>「就业率」的分母是毕业生数还是投递过的人数？</li>
 *   <li>employment_stat 里 major_id 为 NULL 的行是学院汇总，专业级明细能混在一起算吗？</li>
 * </ul>
 *
 * <p>这些是<b>指标口径</b>问题，不是模型能力问题。企业里专门有人维护语义层解决它，
 * 而不是指望大模型自己猜对。实测中，加了口径规则之后，涉及"已签约"过滤的题
 * 从全错变成全对——因为这些题的失败原因根本不是模型不会写 SQL，
 * 而是它不知道业务上该不该加那个条件。
 *
 * <h3>规则怎么写才有效</h3>
 * 写成「什么场景 + 必须怎么做」，不要写成「请注意」这种空话。
 * <pre>
 * ✅ 统计薪资、起薪、平均月薪时，只统计 sign_status = '已签约' 的 offer
 * ❌ 注意薪资统计的正确性
 * </pre>
 */
@ConfigurationProperties(prefix = "askbase.semantic")
public class SemanticProperties {

    /**
     * 口径规则。每条会被拼进 system prompt 的「业务口径」段落。
     *
     * <p>条数控制在 10 条以内。太多会稀释注意力，反而降低遵循率——
     * 这和 prompt 里塞太多示例是同一个道理。
     */
    private List<String> rules = new ArrayList<>();

    /** 是否把口径注入 prompt。关掉可以对比"有口径 vs 无口径"的准确率差异 */
    private boolean enabled = true;

    public List<String> getRules() {
        return rules;
    }

    public void setRules(List<String> rules) {
        this.rules = rules;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
