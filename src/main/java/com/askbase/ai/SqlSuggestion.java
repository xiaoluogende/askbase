package com.askbase.ai;

import java.util.List;

/**
 * 模型返回的结构化结果。
 *
 * @param sql         生成的 SQL
 * @param explanation 自然语言解释，用于前端展示"我理解成了什么"
 * @param chartType   建议的图表类型：table / bar / line / pie
 */
public record SqlSuggestion(
        String sql,
        String explanation,
        String chartType
) {

    private static final List<String> VALID_CHART_TYPES = List.of("table", "bar", "line", "pie");

    public SqlSuggestion {
        // 模型经常返回 "BAR"、"柱状图" 这种值，统一收敛到四种
        chartType = VALID_CHART_TYPES.contains(chartType) ? chartType : "table";
        explanation = explanation == null ? "" : explanation;
    }
}
