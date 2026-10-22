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
                .userWithCorrection("统计课程平均分", "### 表 course",
                        "SQL 执行报错：Unknown column 'c.course_name' in 'field list'");

        assertThat(user).contains("上一次尝试失败了");
        // 原始错误必须原样保留，不能被转述成"列名写错了"之类
        assertThat(user).contains("Unknown column 'c.course_name' in 'field list'");
        assertThat(user).contains("### 表 course");
    }
}
