package com.askbase.ai;

import org.springframework.stereotype.Component;

import java.util.List;

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
 *
 * <p>另外注入 {@link SemanticProperties} 里的业务口径——那是"怎么算才算对"的定义，
 * 表结构描述里给不出来。
 */
@Component
public class PromptBuilder {

    private static final String BASE_PROMPT = """
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

    // 曾经加过第 11 条"只 SELECT 问题所问的列，不要附加辅助列"，本意是修掉模型
    // 多返回 listed_count / total_count 这类列的问题。实测反而更糟：模型把本该展示的列
    // 也删了（"每个城市薪资最高的岗位"只返回岗位名不返回城市），EA 从 88% 掉回 84%。
    // 结论：约束"少返回"的代价大于容忍"多返回"。多返回的列由前端按需隐藏即可。

    private final SemanticProperties semanticProperties;

    public PromptBuilder(SemanticProperties semanticProperties) {
        this.semanticProperties = semanticProperties;
    }

    /**
     * 拼出 system prompt。
     *
     * <p>业务口径单独成段并标明"最高优先级"——混在普通规则里会被稀释。
     * 实测：口径散落在规则中间时，模型遵循率明显低于单独成段。
     */
    public String system() {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);

        if (semanticProperties.isEnabled() && !semanticProperties.getRules().isEmpty()) {
            sb.append("\n业务口径（最高优先级，与上面规则冲突时以本节为准）：\n");
            List<String> rules = semanticProperties.getRules();
            for (int i = 0; i < rules.size(); i++) {
                sb.append(i + 1).append(". ").append(rules.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * @param question   用户原始问题
     * @param schemaText 召回的表结构描述（由 SchemaDocumentBuilder 渲染）
     */
    public String user(String question, String schemaText) {
        return user(question, schemaText, List.of(), null);
    }

    public String user(String question, String schemaText, String correctionFeedback) {
        return user(question, schemaText, List.of(), correctionFeedback);
    }

    /**
     * 组装用户消息：表结构 + 对话历史 + 当前问题 +（可选）纠错提示。
     *
     * <p>历史只放「问题 + SQL」，不放结果集——见 {@link ConversationTurn} 的说明。
     *
     * <p>为什么要明确告诉模型"可能是追问，也可能是新问题"：
     * 如果只说"参考上文"，模型会把所有问题都当成追问，
     * 用户开新话题时它反而被上一轮的 SQL 带偏。给它一个判断依据更稳。
     */
    public String user(String question, String schemaText,
                       List<ConversationTurn> history, String correctionFeedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是可用的表结构：\n\n").append(schemaText).append('\n');

        if (history != null && !history.isEmpty()) {
            sb.append("\n以下是之前几轮对话（用户问题 → 当时生成的 SQL）：\n");
            for (int i = 0; i < history.size(); i++) {
                ConversationTurn t = history.get(i);
                sb.append("\n【第 ").append(i + 1).append(" 轮】\n");
                sb.append("问题：").append(t.question()).append('\n');
                if (t.sql() != null && !t.sql().isBlank()) {
                    sb.append("SQL：").append(t.sql()).append('\n');
                }
            }
            sb.append("""

                    注意：
                    - 如果当前问题是追问（省略了主语、只改动某个条件、或以"那…呢""其中""只看""再"开头），
                      请结合上文理解完整意图，生成一条能独立执行的完整 SQL。
                    - 如果当前问题是全新的独立问题，忽略上文，按当前问题生成。
                    """);
        }

        sb.append("\n当前问题：").append(question).append('\n');

        if (correctionFeedback != null) {
            sb.append("""

                    ⚠️ 上一次尝试失败了，原因如下：
                    """).append(correctionFeedback).append("""

                    请根据上面的错误修正你的查询。注意：
                    - 只修正错误的部分，不要推翻整个思路
                    - 如果错误是"结果为空"，优先检查过滤条件里的枚举值是否和 schema 中的一致
                    - 如果错误是"列不存在"，请严格对照 schema 里的字段名
                    """);
        }
        return sb.toString();
    }
}
