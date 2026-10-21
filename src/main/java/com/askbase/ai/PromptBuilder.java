package com.askbase.ai;

import org.springframework.stereotype.Component;

/**
 * 构造发给模型的 prompt。
 *
 * <p>这里的每一条规则都对应一个真实踩过的坑，不是从别处抄来的"最佳实践"：
 *
 * <ul>
 *   <li><b>只输出 JSON</b>——不加这条，模型会在 JSON 前后加"好的，以下是查询语句："</li>
 *   <li><b>禁止写操作</b>——用户说"清理重复数据"时它会生成 DELETE。虽然下游有校验器兜底，
 *       但在 prompt 里先拦一道能省一次往返</li>
 *   <li><b>字段必须来自 schema</b>——否则会幻觉出不存在的列，报 Unknown column</li>
 *   <li><b>枚举必须用给定取值</b>——否则会编 {@code status = 'success'}，
 *       而实际取值是「已签约/未签约/已违约」，查询永远返回空</li>
 *   <li><b>多表必须写 JOIN 条件</b>——漏写 ON 会产生笛卡尔积，几十万行打爆连接池</li>
 *   <li><b>日期用字符串</b>——模型爱写 {@code DATE_SUB(NOW(), INTERVAL 1 YEAR)}，
 *       和数据集里的历史日期对不上</li>
 * </ul>
 */
@Component
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是 MySQL 专家。根据给定的表结构，把用户的自然语言问题转换成一条 MySQL 查询。

            必须遵守：
            1. 只输出 JSON，不要任何解释文字、不要 markdown 代码块
            2. JSON 结构：{"sql": "...", "explanation": "...", "chartType": "table|bar|line|pie"}
            3. 只能生成 SELECT 语句，禁止 INSERT / UPDATE / DELETE / DROP / ALTER
            4. 表名和字段名必须来自下面的 schema，不要臆造不存在的列
            5. 表名、字段名用反引号包裹
            6. 枚举字段必须使用 schema 中 [取值: ...] 列出的值，不要自己编
            7. 多表查询必须写明确的 JOIN ... ON 条件，禁止产生笛卡尔积
            8. 日期比较用字符串，如 publish_date >= '2025-01-01'
            9. 统计人数用 COUNT(DISTINCT 主键)，不要用 COUNT(*)
            10. 如果问题无法用给定表回答，sql 返回空字符串，并在 explanation 里说明原因

            chartType 选择：
            - 结果只有 1 行 1 列 → table
            - 第一列是时间 → line
            - 第一列是类别且第二列是数值 → bar
            - 表达占比（百分比） → pie
            其他情况一律 table。
            """;

    public String system() {
        return SYSTEM_PROMPT;
    }

    /**
     * @param question   用户原始问题
     * @param schemaText 召回的表结构描述（由 SchemaDocumentBuilder 渲染）
     */
    public String user(String question, String schemaText) {
        return """
                以下是可用的表结构：

                %s

                用户问题：%s
                """.formatted(schemaText, question);
    }
}
