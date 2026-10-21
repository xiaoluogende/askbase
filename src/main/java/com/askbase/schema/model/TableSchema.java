package com.askbase.schema.model;

import java.util.List;

/**
 * 一张表的完整结构元数据。
 *
 * @param name        表名
 * @param comment     表注释
 * @param rowCount    精确行数（小表直接 COUNT(*)；大表应改用 information_schema 的估算值）
 * @param columns     字段列表，按 ordinal_position 排序
 * @param foreignKeys 外键列表
 * @param sampleRows  样例数据，用于让 LLM 理解字段的真实形态（如日期格式、金额量级）
 */
public record TableSchema(
        String name,
        String comment,
        long rowCount,
        List<ColumnSchema> columns,
        List<ForeignKeySchema> foreignKeys,
        List<String> sampleRows
) {
}
