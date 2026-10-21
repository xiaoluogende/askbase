package com.askbase.schema.model;

/**
 * 外键关系。
 *
 * <p>这是 JOIN 能否生成正确的关键。LLM 不知道 {@code offer.student_id} 该连
 * {@code student.id} 还是 {@code student.student_no}——必须显式告诉它。
 *
 * <p>统计发现：多表查询生成错误里，最大的一类是「漏写或写错 JOIN 条件」，
 * 直接导致笛卡尔积。把外键关系写进 schema 描述，能显著降低这类错误。
 */
public record ForeignKeySchema(
        String column,
        String refTable,
        String refColumn
) {
}
