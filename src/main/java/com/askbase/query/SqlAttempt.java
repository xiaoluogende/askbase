package com.askbase.query;

/**
 * 一次生成-执行尝试的记录。
 *
 * <p>为什么要留这个：自纠错的价值不能靠"感觉变好了"来证明。
 * 有了每轮的 SQL 和失败原因，你能在评估里直接统计"多少题是靠纠错救回来的"，
 * 也能看出模型在哪类错误上反复犯同样的错。
 *
 * @param index    第几轮，从 1 开始
 * @param sql      这一轮生成的 SQL（校验未通过时是模型原始输出）
 * @param rowCount 执行返回的行数，执行失败时为 null
 * @param failure  失败原因，成功时为 null
 */
public record SqlAttempt(
        int index,
        String sql,
        Integer rowCount,
        String failure
) {

    public boolean success() {
        return failure == null;
    }
}
