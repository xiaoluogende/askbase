package com.askbase.schema;

import com.askbase.schema.model.ColumnSchema;
import com.askbase.schema.model.ForeignKeySchema;
import com.askbase.schema.model.SchemaDocument;
import com.askbase.schema.model.TableSchema;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 把 {@link TableSchema} 渲染成可检索 / 可喂给模型的文本。
 *
 * <p>这个类的产出直接决定两件事：召回准不准、模型写不写得对。所以格式不是随便定的：
 *
 * <ul>
 *   <li><b>表名和表注释放第一行</b>——检索时权重最高，也是模型判断"这张表是不是我要的"的第一依据</li>
 *   <li><b>枚举取值单独标 {@code [取值: ...]}</b>——模型看到这个就不会编 {@code status='success'}</li>
 *   <li><b>外键写成 {@code a → b}</b>——比自然语言描述更省 token，也更不容易看错</li>
 *   <li><b>样例数据用 {@code 列=值}</b>——让模型知道日期是 {@code 2022-05-06} 而不是时间戳、
 *       金额是 14900 而不是 149.00</li>
 * </ul>
 *
 * <p>实测经验：同一份 schema，注释写「岗位表」和写「招聘岗位表。薪资为月薪区间」，
 * 召回效果差一个数量级。**注释就是 prompt**，这话不是比喻。
 */
@Component
public class SchemaDocumentBuilder {

    public SchemaDocument build(TableSchema table) {
        String text = renderText(table);
        return new SchemaDocument(
                table.name(),
                text,
                renderHeader(table),
                renderSearchable(table),
                estimateTokens(text));
    }

    /** 表名 + 表注释。检索时权重最高的一层 */
    private String renderHeader(TableSchema table) {
        return (table.name() + " " + table.comment()).toLowerCase();
    }

    /** 完整描述：喂给模型的版本 */
    private String renderText(TableSchema table) {
        StringBuilder sb = new StringBuilder(512);

        sb.append("### 表 ").append(table.name());
        if (!table.comment().isBlank()) {
            sb.append(" —— ").append(table.comment());
        }
        sb.append('\n');
        sb.append("行数: ").append(table.rowCount()).append('\n');

        sb.append("字段:\n");
        for (ColumnSchema c : table.columns()) {
            sb.append("  ").append(c.name()).append(' ').append(c.type().toUpperCase());
            if (c.primaryKey()) {
                sb.append(" PK");
            }
            sb.append(" | ").append(c.comment().isBlank() ? "(无注释)" : c.comment());
            if (c.isEnum()) {
                sb.append("  [取值: ").append(String.join("、", c.enumValues())).append(']');
            }
            sb.append('\n');
        }

        if (!table.foreignKeys().isEmpty()) {
            sb.append("外键: ").append(table.foreignKeys().stream()
                    .map(SchemaDocumentBuilder::renderFk)
                    .collect(Collectors.joining(", "))).append('\n');
        }

        if (!table.sampleRows().isEmpty()) {
            sb.append("样例:\n");
            for (String row : table.sampleRows()) {
                sb.append("  ").append(row).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 字段级检索文本：字段名、字段注释、枚举值、外键指向的表。
     *
     * <p>故意<b>不含</b>表名和表注释——那部分在 {@link #renderHeader} 里单独计分。
     * 如果两处都放，表注释会因为是长文本而虚高，掩盖字段级信息。
     */
    private String renderSearchable(TableSchema table) {
        StringBuilder sb = new StringBuilder(256);
        for (ColumnSchema c : table.columns()) {
            sb.append(c.name()).append(' ').append(c.comment()).append(' ');
            if (c.isEnum()) {
                sb.append(String.join(" ", c.enumValues())).append(' ');
            }
        }
        for (ForeignKeySchema fk : table.foreignKeys()) {
            sb.append(fk.refTable()).append(' ');
        }
        return sb.toString().toLowerCase();
    }

    private static String renderFk(ForeignKeySchema fk) {
        return fk.column() + " → " + fk.refTable() + "." + fk.refColumn();
    }

    /**
     * token 数粗估。
     *
     * <p>中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token。
     * 这个估算不精确，用途只是"判断这段会不会太长"，不用于计费。
     * 要精确值得用对应模型的分词器，那是做成本统计时的事。
     */
    private static int estimateTokens(String text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x2E80) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + other / 4;
    }
}
