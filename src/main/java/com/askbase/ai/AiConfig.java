package com.askbase.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手工装配模型 Bean。
 *
 * <h3>为什么不用 spring-ai-starter-model-openai</h3>
 * starter 会从 {@code spring.ai.openai.api-key} 自动装配 ChatModel 和 EmbeddingModel，
 * 但两者共用同一个 {@code base-url}。本项目聊天走 DeepSeek、embedding 走 DashScope，
 * 是两个厂商、两个域名，starter 表达不了。
 *
 * <p>手工装配的附带好处：<b>没配 Key 时应用照样能启动</b>。Bean 按条件创建，
 * 缺少配置只是少一个能力，而不是启动失败——本地开发时这很重要。
 *
 * <p>用 {@code @ConditionalOnExpression} 而不是 {@code @ConditionalOnBean}：
 * 后者依赖 Bean 定义的解析顺序，在组件扫描场景下不可靠。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    @ConditionalOnExpression("'${askbase.ai.chat.api-key:}' != ''")
    public ChatModel askbaseChatModel(AiProperties properties) {
        AiProperties.Chat chat = properties.getChat();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                // 显式指定路径，不依赖 Spring AI 的默认值——
                // 默认值随版本变过，而这类错误只会表现为一句 404
                .completionsPath("/v1/chat/completions")
                .build();

        ChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chat.getModel())
                        .temperature(chat.getTemperature())
                        .maxTokens(chat.getMaxTokens())
                        .build())
                .build();

        log.info("对话模型已装配：{} @ {}", chat.getModel(), chat.getBaseUrl());
        return model;
    }

    @Bean
    @ConditionalOnExpression("'${askbase.ai.embedding.api-key:}' != ''")
    public EmbeddingModel askbaseEmbeddingModel(AiProperties properties) {
        AiProperties.Embedding embedding = properties.getEmbedding();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .embeddingsPath("/v1/embeddings")
                .build();

        // EmbeddingModel 没有 builder，只能走构造器
        EmbeddingModel model = new OpenAiEmbeddingModel(
                api,
                MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder()
                        .model(embedding.getModel())
                        .dimensions(embedding.getDimensions())
                        .build());

        log.info("向量模型已装配：{} @ {}（{} 维）",
                embedding.getModel(), embedding.getBaseUrl(), embedding.getDimensions());
        return model;
    }
}
