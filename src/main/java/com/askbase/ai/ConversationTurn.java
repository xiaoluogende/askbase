package com.askbase.ai;

/**
 * 一轮对话的历史记录。
 *
 * <p>放在 ai 包而不是 query 包：它是「生成」的输入，这样依赖方向是
 * {@code query → ai}，不会反过来。
 *
 * <p>为什么只留「问题 + SQL」而不是完整结果集：
 * <ol>
 *   <li><b>token 成本</b>——一次查询可能返回 1000 行，塞进 prompt 直接爆</li>
 *   <li><b>模型需要的是意图，不是数据</b>。追问"那 2024 届呢"，
 *       模型要知道上一轮问的是"各专业平均薪资"、SQL 长什么样，
 *       而不是上一轮具体返回了哪 6 行</li>
 *   <li>SQL 本身携带了完整的查询结构（表、过滤、聚合），信息密度最高</li>
 * </ol>
 *
 * <p>服务端不保存会话——历史由客户端随请求带上。
 * 这样服务端是无状态的：不用管会话过期、不会内存泄漏、可以直接水平扩展。
 *
 * @param question 用户当轮的原话
 * @param sql      当轮最终执行的 SQL（已补 LIMIT），失败时为 null
 */
public record ConversationTurn(
        String question,
        String sql
) {
}
