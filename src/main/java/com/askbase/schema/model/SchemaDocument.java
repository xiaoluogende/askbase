package com.askbase.schema.model;

/**
 * 一张表的「可检索文本」。
 *
 * <p>为什么要单独抽象一层，而不是直接把 {@link TableSchema} 塞进 prompt：
 * <ol>
 *   <li><b>检索和生成需要不同粒度的文本</b>。检索要短、要覆盖关键词；
 *       生成要全、要包含类型和样例。两者分开才能各自优化</li>
 *   <li><b>文本格式决定召回率</b>。同一个表，写成「岗位表」和写成
 *       「招聘岗位表。薪资为月薪区间」召回效果完全不同——后者才能匹配「薪资」这个查询</li>
 *   <li>将来加向量化时，需要嵌入的就是 {@link #text()}</li>
 * </ol>
 *
 * @param table         表名
 * @param text          完整描述文本（用于 prompt）
 * @param header        表名 + 表注释。检索时权重最高——它回答的是"这张表是什么"，
 *                      而字段注释只回答"某个字段是什么"。用户问「专业」，
 *                      命中 {@code major} 的表注释比命中 {@code college} 的字段注释更相关
 * @param searchable    字段级检索文本（字段名 + 字段注释 + 枚举值 + 外键指向的表），
 *                      <b>不含</b>表名和表注释——否则会和 {@link #header} 重复计分
 * @param tokenEstimate token 数粗估，用于控制 prompt 预算
 */
public record SchemaDocument(
        String table,
        String text,
        String header,
        String searchable,
        int tokenEstimate
) {

    /** 兼容早期版本存进 Redis 的 JSON（没有 header 字段） */
    public String headerOrEmpty() {
        return header == null ? "" : header;
    }

    public String searchableOrEmpty() {
        return searchable == null ? "" : searchable;
    }
}
