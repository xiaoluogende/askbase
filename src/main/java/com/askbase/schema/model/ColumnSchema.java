package com.askbase.schema.model;

import java.util.List;

/**
 * 一个字段的元数据。
 *
 * @param name       字段名
 * @param type       原始类型，如 {@code varchar(32)}、{@code decimal(3,2)}
 * @param nullable   是否允许 NULL
 * @param primaryKey 是否主键
 * @param comment    字段注释（数据库里的 COMMENT）
 * @param enumValues 低基数列的取值样本。这是给 LLM 的关键提示——
 *                   它知道 {@code sign_status} 的取值是「已签约 / 未签约 / 已违约」，
 *                   才不会写出 {@code WHERE sign_status = 'success'} 这种幻觉
 */
public record ColumnSchema(
        String name,
        String type,
        boolean nullable,
        boolean primaryKey,
        String comment,
        List<String> enumValues
) {

    /** 是否被判定为枚举型（取值少、可穷举） */
    public boolean isEnum() {
        return enumValues != null && !enumValues.isEmpty();
    }
}
