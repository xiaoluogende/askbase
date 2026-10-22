package com.askbase.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排结果解析的测试。
 *
 * <p>这段逻辑必须稳：模型返回的表名会直接被拼进 prompt 去查 schema，
 * 如果透传了一个不存在的表，下游会拼出空 schema，模型就只能瞎猜。
 */
class TableRerankerTest {

    private final TableReranker reranker = new TableReranker(null, new com.fasterxml.jackson.databind.ObjectMapper());

    private static final List<TableReranker.Candidate> CANDIDATES = List.of(
            new TableReranker.Candidate("student", "学生基本信息表"),
            new TableReranker.Candidate("offer", "Offer发放记录表"),
            new TableReranker.Candidate("job_posting", "招聘岗位表")
    );

    @Test
    void 正常数组应保持顺序() {
        assertThat(reranker.parse("[\"student\",\"offer\"]", CANDIDATES))
                .containsExactly("student", "offer");
    }

    @Test
    void 应剥掉markdown围栏() {
        String content = "```json\n[\"job_posting\",\"offer\"]\n```";
        assertThat(reranker.parse(content, CANDIDATES))
                .containsExactly("job_posting", "offer");
    }

    @Test
    void 应忽略JSON前后的废话() {
        String content = "好的，以下是需要的表：[\"offer\"]";
        assertThat(reranker.parse(content, CANDIDATES)).containsExactly("offer");
    }

    @Test
    void 不存在的表名必须被过滤() {
        // 模型幻觉出一个不存在的表，如果透传会让下游拼出空 schema
        assertThat(reranker.parse("[\"student\",\"nonexistent_table\",\"offer\"]", CANDIDATES))
                .containsExactly("student", "offer");
    }

    @Test
    void 应去重() {
        assertThat(reranker.parse("[\"offer\",\"offer\",\"student\"]", CANDIDATES))
                .containsExactly("offer", "student");
    }

    @Test
    void 大小写应归一化() {
        assertThat(reranker.parse("[\"Student\",\"OFFER\"]", CANDIDATES))
                .containsExactly("student", "offer");
    }

    @Test
    void 空数组应返回空() {
        assertThat(reranker.parse("[]", CANDIDATES)).isEmpty();
    }

    @Test
    void 非JSON应返回空而不是抛异常() {
        assertThat(reranker.parse("我不知道该用哪张表", CANDIDATES)).isEmpty();
        assertThat(reranker.parse("", CANDIDATES)).isEmpty();
        assertThat(reranker.parse(null, CANDIDATES)).isEmpty();
    }

    @Test
    void 全是幻觉表名时应返回空() {
        // 返回空会让调用方回退到粗排结果，而不是用一堆不存在的表
        assertThat(reranker.parse("[\"foo\",\"bar\"]", CANDIDATES)).isEmpty();
    }
}
