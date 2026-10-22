package com.askbase.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt 构造的测试。
 *
 * <p>这类"拼字符串"的代码最容易悄悄坏掉——改个格式、漏个换行，
 * 模型行为就变了，但编译和运行都不报错。所以必须有测试钉住关键内容。
 */
class PromptBuilderTest {

    private static PromptBuilder builder(List<String> rules, boolean enabled) {
        SemanticProperties props = new SemanticProperties();
        props.setRules(rules);
        props.setEnabled(enabled);
        return new PromptBuilder(props);
    }

    @Test
    void 基础规则必须始终存在() {
        String prompt = builder(List.of(), true).system();

        assertThat(prompt).contains("只输出 JSON");
        assertThat(prompt).contains("只能生成 SELECT");
        assertThat(prompt).contains("chartType");
    }

    @Test
    void 口径规则应被注入() {
        String prompt = builder(List.of(
                "统计薪资时只算已签约的 offer",
                "就业率的分母是毕业生数"
        ), true).system();

        assertThat(prompt).contains("业务口径");
        assertThat(prompt).contains("1. 统计薪资时只算已签约的 offer");
        assertThat(prompt).contains("2. 就业率的分母是毕业生数");
    }

    @Test
    void 口径应标为最高优先级() {
        // 这一句是给模型看的，不是给人看的。
        // 实测把它去掉之后，模型遇到口径和通用规则冲突时会自己挑一个——
        // 而它挑的往往不是你要的那个
        String prompt = builder(List.of("某条口径"), true).system();

        assertThat(prompt).contains("最高优先级");
    }

    @Test
    void 关闭口径后不应出现() {
        String prompt = builder(List.of("这条不该出现"), false).system();

        assertThat(prompt).doesNotContain("业务口径");
        assertThat(prompt).doesNotContain("这条不该出现");
    }

    @Test
    void 没有规则时不应留空的业务口径标题() {
        // 标题在但下面一条规则都没有，会干扰模型注意力
        String prompt = builder(List.of(), true).system();

        assertThat(prompt).doesNotContain("业务口径");
    }

    @Test
    void 首轮消息应包含schema和问题() {
        String user = builder(List.of(), true).user("各专业平均薪资", "### 表 offer\n...");

        assertThat(user).contains("### 表 offer");
        assertThat(user).contains("各专业平均薪资");
        assertThat(user).doesNotContain("上一次尝试失败");
    }

    @Test
    void 纠错消息应包含原始错误() {
        String user = builder(List.of(), true)
                .user("统计课程平均分", "### 表 course",
                        "SQL 执行报错：Unknown column 'c.course_name' in 'field list'");

        assertThat(user).contains("上一次尝试失败了");
        // 原始错误必须原样保留，不能被转述成"列名写错了"之类
        assertThat(user).contains("Unknown column 'c.course_name' in 'field list'");
        assertThat(user).contains("### 表 course");
    }

    @Test
    void 无历史时不应出现对话段落() {
        String user = builder(List.of(), true).user("各专业平均薪资", "### 表 offer", List.of(), null);

        assertThat(user).doesNotContain("之前几轮对话");
        assertThat(user).doesNotContain("追问");
        assertThat(user).contains("当前问题：各专业平均薪资");
    }

    @Test
    void 有历史时应带上前几轮的问题和SQL() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("各专业分别有多少名学生？", "SELECT m.name, COUNT(*) FROM ..."),
                new ConversationTurn("那 2023 届呢？", "SELECT m.name, COUNT(*) FROM ... WHERE graduation_year=2023")
        );

        String user = builder(List.of(), true)
                .user("只看2024届毕业生的", "### 表 student", history, null);

        assertThat(user).contains("之前几轮对话");
        assertThat(user).contains("【第 1 轮】");
        assertThat(user).contains("【第 2 轮】");
        assertThat(user).contains("各专业分别有多少名学生？");
        assertThat(user).contains("graduation_year=2023");
        assertThat(user).contains("当前问题：只看2024届毕业生的");
    }

    @Test
    void 有历史时必须告诉模型可能是追问也可能是新问题() {
        // 只给历史不说判断依据的话，模型会把所有问题都当追问，
        // 用户开新话题时反而被上一轮的 SQL 带偏
        List<ConversationTurn> history = List.of(
                new ConversationTurn("各专业有多少学生", "SELECT ..."));

        String user = builder(List.of(), true).user("今天天气", "### 表 student", history, null);

        assertThat(user).contains("追问");
        assertThat(user).contains("全新的独立问题");
    }

    @Test
    void 历史里SQL为空时不应渲染出null() {
        List<ConversationTurn> history = List.of(new ConversationTurn("上一轮失败了", null));

        String user = builder(List.of(), true).user("再试一次", "### 表 student", history, null);

        assertThat(user).doesNotContain("SQL：null");
        assertThat(user).contains("上一轮失败了");
    }

    @Test
    void 历史与纠错可以同时存在() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("各专业有多少学生", "SELECT ..."));

        String user = builder(List.of(), true)
                .user("只看2024届", "### 表 student", history, "Unknown column 'x'");

        assertThat(user).contains("之前几轮对话");
        assertThat(user).contains("上一次尝试失败了");
        assertThat(user).contains("Unknown column 'x'");
    }
}
