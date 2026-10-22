package com.askbase.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 表召回的重排（rerank）。
 *
 * <h3>为什么需要重排</h3>
 * 第一阶段的召回（关键词 + 向量）是"粗排"：它只看词面重合和语义距离，
 * 不知道"回答这个问题到底需要哪几张表"。
 *
 * <p>实测的问题：本项目的 10 张表都围绕"学生就业"，向量相似度全挤在 0.45–0.55 之间，
 * 混合召回的分数集中在 2.4–3.0。排序几乎是随机的——问"签约工作地在西安有多少人"，
 * 真正需要的 {@code job_posting} 排在第 7，被 topK=6 切掉，模型于是拒答。
 *
 * <p>重排用一次轻量的 LLM 调用解决这个问题：把候选表（表名 + 一句注释）和问题一起给模型，
 * 让它直接判断"要用哪些表、按什么顺序"。这比调权重有效得多，因为它在做语义判断，
 * 而不是在比较两个不可比的分数。
 *
 * <h3>成本</h3>
 * 每次问答多一次模型调用，约 300 输入 token / 20 输出 token。
 * 相比主生成（1500+ token），这个开销可以接受。
 *
 * <p>模型不可用时返回空列表，调用方回退到第一阶段的结果——重排失败不应该让整个问答失败。
 */
@Service
public class TableReranker {

    private static final Logger log = LoggerFactory.getLogger(TableReranker.class);

    private static final String SYSTEM_PROMPT = """
            你是数据库查询专家。给你一个用户问题，和一批候选数据表（表名 + 注释），
            请判断回答这个问题需要用到哪些表，并按重要性排序。

            只输出 JSON 数组，例如：["student","offer"]
            不要输出任何解释文字，不要 markdown 代码块。
            最多返回 5 张表。如果这些表都无法回答问题，输出 []。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public TableReranker(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return chatModelProvider.getIfAvailable() != null;
    }

    /**
     * @param question   用户问题
     * @param candidates 第一阶段召回的候选表（表名 + 简短说明）
     * @return 按重要性排序的表名；失败时返回空列表，调用方应回退
     */
    public List<String> rerank(String question, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return List.of();
        }

        String userMessage = buildUserMessage(question, candidates);
        String content;
        try {
            content = ChatClient.builder(chatModel).build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("表重排调用失败，回退到粗排结果：{}", e.getMessage());
            return List.of();
        }

        List<String> ranked = parse(content, candidates);
        if (ranked.isEmpty()) {
            log.debug("重排返回空或解析失败，回退到粗排结果：{}", content);
        }
        return ranked;
    }

    private String buildUserMessage(String question, List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n候选表：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            sb.append(i + 1).append(". ").append(c.table()).append(" —— ").append(c.summary()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析模型返回的表名数组。
     *
     * <p>做三件事：
     * <ol>
     *   <li>剥掉 markdown 围栏和前后废话（模型不一定听话）</li>
     *   <li>只保留<b>候选里真实存在</b>的表名——模型可能编出一个不存在的表，
     *       直接透传会让下游拼出空 schema</li>
     *   <li>去重，保持顺序</li>
     * </ol>
     */
    List<String> parse(String content, List<Candidate> candidates) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String json = content.strip();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        json = json.substring(start, end + 1);

        java.util.Set<String> valid = new java.util.HashSet<>();
        for (Candidate c : candidates) {
            valid.add(c.table().toLowerCase());
        }

        List<String> result = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            for (JsonNode n : node) {
                String t = n.asText("").strip().toLowerCase();
                if (valid.contains(t) && !result.contains(t)) {
                    result.add(t);
                }
            }
        } catch (Exception e) {
            log.debug("重排结果解析失败：{}", content);
            return List.of();
        }
        return result;
    }

    /**
     * @param table   表名
     * @param summary 一句话说明（表注释的首句，太长会浪费 token）
     */
    public record Candidate(String table, String summary) {
    }
}
