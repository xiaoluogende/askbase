package com.askbase.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置。
 *
 * <p>聊天和 embedding 分开配，因为它们指向不同厂商：
 * DeepSeek 只有对话模型、没有 embedding；DashScope 两者都有但对话能力不如 DeepSeek。
 * 各取所长是最常见的生产组合。
 */
@ConfigurationProperties(prefix = "askbase.ai")
public class AiProperties {

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public static class Chat {
        /** OpenAI 兼容端点。DeepSeek 是 https://api.deepseek.com */
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        /**
         * 生成 SQL 不需要创造性，温度必须为 0。
         * 温度大于 0 会让同一个问题两次生成不同的 SQL，评估结果无法复现。
         */
        private double temperature = 0.0;
        private int maxTokens = 1024;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Embedding {
        /**
         * ⚠️ 注意这里<b>不带</b> {@code /v1}。
         *
         * <p>Spring AI 会在 base-url 后面自己拼 {@code /v1/embeddings}。
         * 如果写成 DashScope 文档里的 {@code .../compatible-mode/v1}，
         * 实际请求会变成 {@code .../v1/v1/embeddings}，直接 404。
         * 这个坑很隐蔽——报错只有一句 "404"，没有任何提示。
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey = "";
        /** text-embedding-v3 是 1024 维；改模型时这里要跟着改，否则向量索引维度对不上 */
        private String model = "text-embedding-v3";
        private int dimensions = 1024;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }
}
