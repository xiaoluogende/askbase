package com.askbase.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答链路的行为配置。
 *
 * <p>注意前缀是 {@code askbase.query}，而 {@link ReadOnlyDataSourceProperties} 是
 * {@code askbase.query.readonly}——两者是包含关系，Spring 会各自绑定，
 * {@code readonly} 这个子键对当前类来说是未知字段，默认被忽略。
 */
@ConfigurationProperties(prefix = "askbase.query")
public class QueryProperties {

    /**
     * 最多尝试几轮（含首轮）。设为 1 等于关闭自纠错。
     *
     * <p>默认 2：一轮首生成 + 一轮纠错。再往上加收益递减——
     * 同一个模型在同一份 prompt 下连续两次写错，第三次多半还是错的，
     * 而每次重试都是一次模型调用。
     */
    private int maxAttempts = 2;

    /**
     * 结果为空时是否重试。
     *
     * <p>这是一个有争议的开关：空结果可能是正确答案（比如"没有违约的 offer"）。
     * 但实测里，空结果更多是枚举值写错或 JOIN 条件错导致的假空。
     * 打开它，代价是本来该返回空的问题会多花一次调用。
     */
    private boolean retryOnEmptyResult = true;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isRetryOnEmptyResult() {
        return retryOnEmptyResult;
    }

    public void setRetryOnEmptyResult(boolean retryOnEmptyResult) {
        this.retryOnEmptyResult = retryOnEmptyResult;
    }
}
