package com.askbase.ai;

import com.askbase.common.BizException;
import com.askbase.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 调用大模型生成 SQL。
 *
 * <p>用 {@link ObjectProvider} 注入 ChatModel 而不是直接注入：没配 Key 时这个 Bean 不存在，
 * 直接注入会让整个应用起不来。这里改成"用到时才检查"，缺 Key 时抛出明确的业务异常。
 */
@Service
public class SqlGenerator {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerator.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public SqlGenerator(ObjectProvider<ChatModel> chatModelProvider,
                        PromptBuilder promptBuilder,
                        ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return chatModelProvider.getIfAvailable() != null;
    }

    public GenerationResult generate(String question, String schemaText) {
        return generate(question, schemaText, null);
    }

    /**
     * @param correctionFeedback 上一轮失败的原因。为 {@code null} 表示首轮生成。
     *                           非空时会把错误原样附在用户消息里，让模型定向修正，
     *                           而不是从头再猜一遍
     */
    public GenerationResult generate(String question, String schemaText, String correctionFeedback) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.LLM_ERROR,
                    "未配置对话模型 API Key。请在 .env 里填 DEEPSEEK_API_KEY 后重启");
        }

        String userMessage = correctionFeedback == null
                ? promptBuilder.user(question, schemaText)
                : promptBuilder.userWithCorrection(question, schemaText, correctionFeedback);

        long start = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = ChatClient.builder(chatModel).build()
                    .prompt()
                    .system(promptBuilder.system())
                    .user(userMessage)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            throw new BizException(ErrorCode.LLM_ERROR, "模型调用失败：" + e.getMessage());
        }

        String content = response.getResult().getOutput().getText();
        long elapsed = System.currentTimeMillis() - start;
        log.debug("模型返回（{} ms）：{}", elapsed, content);

        SqlSuggestion suggestion = parse(content);
        return new GenerationResult(suggestion, elapsed, promptTokens(response), completionTokens(response));
    }

    /**
     * 解析模型输出。
     *
     * <p>即使 prompt 里明确说了"只输出 JSON"，模型仍然可能加 markdown 代码块或者
     * 一段"好的，以下是查询："。所以解析必须宽容：先剥围栏，再截取第一个 { 到最后一个 }。
     * <b>不要假设模型一定听话。</b>
     */
    SqlSuggestion parse(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.LLM_ERROR, "模型返回为空");
        }

        String json = content.strip();

        // 剥 ```json ... ``` 围栏
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) {
                json = json.substring(firstNewline + 1);
            }
            int fence = json.lastIndexOf("```");
            if (fence >= 0) {
                json = json.substring(0, fence);
            }
            json = json.strip();
        }

        // 截取最外层 JSON 对象
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BizException(ErrorCode.LLM_ERROR, "模型输出里找不到 JSON：" + abbreviate(content));
        }
        json = json.substring(start, end + 1);

        try {
            JsonNode node = objectMapper.readTree(json);
            String sql = node.path("sql").asText("").strip();
            String explanation = node.path("explanation").asText("");
            String chartType = node.path("chartType").asText("table").toLowerCase();

            if (sql.isEmpty()) {
                // 模型判断"无法回答"时走这条路，属于正常分支，不是错误
                throw new BizException(ErrorCode.LLM_ERROR,
                        "模型认为无法用现有表回答：" + (explanation.isBlank() ? "（无说明）" : explanation));
            }
            return new SqlSuggestion(sql, explanation, chartType);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.LLM_ERROR, "解析模型输出失败：" + abbreviate(content));
        }
    }

    private static int promptTokens(ChatResponse response) {
        try {
            return response.getMetadata().getUsage().getPromptTokens();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int completionTokens(ChatResponse response) {
        try {
            return response.getMetadata().getUsage().getCompletionTokens();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /**
     * @param suggestion       结构化结果
     * @param elapsedMs        模型耗时
     * @param promptTokens     输入 token 数（用于成本统计）
     * @param completionTokens 输出 token 数
     */
    public record GenerationResult(
            SqlSuggestion suggestion,
            long elapsedMs,
            int promptTokens,
            int completionTokens
    ) {
    }
}
