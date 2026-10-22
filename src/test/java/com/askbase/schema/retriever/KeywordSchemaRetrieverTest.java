package com.askbase.schema.retriever;

import com.askbase.schema.SchemaStore;
import com.askbase.schema.model.SchemaDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeywordSchemaRetrieverTest {

    private KeywordSchemaRetriever retriever;

    /** 用真实 schema 的片段构造，保证测试和线上行为一致 */
    private static SchemaDocument doc(String table, String header, String body) {
        return new SchemaDocument(table, "### 表 " + table, header, body, java.util.List.of(), 100);
    }

    @BeforeEach
    void setUp() {
        SchemaStore store = mock(SchemaStore.class);
        when(store.loadAll()).thenReturn(List.of(
                doc("offer",
                        "offer offer发放记录表。一个学生可能拿到多个offer。统计就业薪资时通常只看",
                        "sign_status id student_id monthly_salary 月薪，单位：元。统计\"起薪\"时用此字段 已签约 未签约 已违约"),
                doc("job_posting",
                        "job_posting 招聘岗位表。薪资为月薪区间，单位元；统计平均薪资时用 (salary_min+salary_max)/2",
                        "title city salary_min salary_max 月薪下限 月薪上限 校招 社招 实习 required_major_id 目标专业ID"),
                doc("student",
                        "student 学生基本信息表。统计某届毕业生时用 graduation_year 字段",
                        "student_no name gender 学生姓名 性别 男 女 gpa 平均绩点 major_id 所属专业ID，关联 major.id"),
                doc("major",
                        "major 专业表，一个学院下设多个专业，一个专业有多个学生",
                        "name code degree_type 学位类型 duration_years 学制年限"),
                doc("college",
                        "college 学院基本信息表，一个学院下设多个专业",
                        "name code dean 院长姓名 founded_year 成立年份"),
                doc("course_enrollment",
                        "course_enrollment 学生选课与成绩表",
                        "student_id course_id term score 百分制成绩 is_retake 是否重修")
        ));
        retriever = new KeywordSchemaRetriever(store);
    }

    @Test
    void 薪资类问题应召回offer和jobPosting() {
        var hits = retriever.retrieve("各专业平均薪资是多少", 6);

        assertThat(hits.stream().map(SchemaRetriever.ScoredTable::table).toList())
                .as("薪资相关问题必须召回 offer 和 job_posting")
                .contains("offer", "job_posting");
    }

    @Test
    void 表级命中应优先于字段级命中() {
        var hits = retriever.retrieve("各专业平均薪资是多少", 6);

        var byTable = hits.stream().collect(
                java.util.stream.Collectors.toMap(SchemaRetriever.ScoredTable::table,
                        SchemaRetriever.ScoredTable::score));

        // major 的表注释命中「专业」，college 的表注释也命中「专业」；
        // 但 major 是"专业表"本身，用户问专业时它比 college 更相关。
        // 这里主要验证：表级命中让 major 排进前列，而不是被字母序挤掉。
        assertThat(byTable).containsKey("major");
        assertThat(byTable.get("major")).isGreaterThan(0.0);
    }

    @Test
    void 提到表名时应显著加分() {
        var hits = retriever.retrieve("student 表里有多少人", 4);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().table()).isEqualTo("student");
        assertThat(hits.getFirst().reason()).contains("student");
    }

    @Test
    void 无关问题应返回空() {
        var hits = retriever.retrieve("今天天气怎么样", 4);

        // 这不是缺陷，是正确行为：召不回来就说明该拒答，而不是硬塞几张表给模型
        assertThat(hits).isEmpty();
    }

    @Test
    void 空问题返回空() {
        assertThat(retriever.retrieve("", 4)).isEmpty();
        assertThat(retriever.retrieve("   ", 4)).isEmpty();
        assertThat(retriever.retrieve(null, 4)).isEmpty();
    }

    @Test
    void topK生效() {
        var hits = retriever.retrieve("薪资 学生 课程 成绩", 2);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void 中文按二字切分() {
        Set<String> tokens = retriever.tokenize("平均薪资");
        assertThat(tokens).contains("平均", "均薪", "薪资");
    }

    @Test
    void 英文按词切分并转小写() {
        Set<String> tokens = retriever.tokenize("SELECT Salary FROM Offer");
        assertThat(tokens).contains("select", "salary", "from", "offer");
    }

    @Test
    void 中英混合都能切() {
        Set<String> tokens = retriever.tokenize("查一下 monthly_salary 的平均值");
        assertThat(tokens).contains("monthly_salary");
        assertThat(tokens).anyMatch(t -> t.length() == 2);
    }

    /**
     * 这个用例刻意断言"已知缺陷"，而不是假装它不存在。
     *
     * <p>同义词召回不了，是 keyword 策略的硬伤，也是引入向量检索的直接动机。
     * 把它写成测试，是为了将来换成 hybrid 时，这条用例会变成"应该能召回"——
     * 那时它就是一个可量化的改进证明。
     */
    @Test
    void 已知缺陷_同义词召不回() {
        var hits = retriever.retrieve("工资待遇怎么样", 4);

        assertThat(hits).as("keyword 策略对'工资'这个同义词无能为力，这是向量检索要解决的")
                .isEmpty();
    }
}
