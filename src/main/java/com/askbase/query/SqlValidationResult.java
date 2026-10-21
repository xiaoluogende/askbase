package com.askbase.query;

import java.util.List;

/**
 * SQL 校验结果。
 *
 * @param valid      是否通过
 * @param sql        校验后的 SQL。注意它<b>可能和输入不同</b>——没写 LIMIT 会被自动补上，
 *                   所以后续执行必须用这个字段，而不是原始输入
 * @param tables     涉及的物理表（已去重、去反引号、小写），用于权限校验
 * @param violations 违规原因，{@code valid=false} 时非空
 */
public record SqlValidationResult(
        boolean valid,
        String sql,
        List<String> tables,
        List<String> violations
) {

    public static SqlValidationResult ok(String sql, List<String> tables) {
        return new SqlValidationResult(true, sql, tables, List.of());
    }

    public static SqlValidationResult reject(String originalSql, List<String> violations) {
        return new SqlValidationResult(false, originalSql, List.of(), violations);
    }
}
