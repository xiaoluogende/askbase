package com.askbase.query;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果。
 *
 * @param columns   列名，保持 SQL 里的顺序
 * @param rows      行数据，key 是列名。用 {@link java.util.LinkedHashMap} 保证顺序
 * @param rowCount  实际返回行数
 * @param truncated 是否因为超过行数上限被截断。前端应该提示用户"结果不完整"
 * @param elapsedMs 执行耗时（毫秒）
 */
public record SqlQueryResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        long elapsedMs
) {
}
