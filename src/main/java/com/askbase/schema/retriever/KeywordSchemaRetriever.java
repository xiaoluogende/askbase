package com.askbase.schema.retriever;

import com.askbase.schema.SchemaStore;
import com.askbase.schema.model.SchemaDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词召回（baseline）。
 *
 * <p>没有 embedding 也能跑，是召回的 baseline。它的价值不在效果好，而在于
 * <b>提供一个可对比的 baseline</b>——加上向量检索之后，要能说出
 * "召回率从多少提到了多少"，否则"我加了 RAG"这句话没有任何说服力。
 *
 * <h3>中文为什么要用 bigram</h3>
 * 中文没有空格，{@code "平均起薪"} 用空格切分是一个词，和文档里的 {@code "起薪"}
 * 对不上。退化成 2-gram（{@code 平均 / 均起 / 起薪}）就能命中。
 * 这是没有分词器时的标准做法，代价是会产生一些无意义组合（如 {@code 均起}），
 * 但它们通常匹配不到东西，不影响排序。
 *
 * <h3>它的已知短板（别掩饰，向量检索要解决的就是这些）</h3>
 * <ul>
 *   <li><b>同义词无能为力</b>：用户问"工资"，注释写"月薪"，一个词都不重合，召回为 0</li>
 *   <li><b>语序无关</b>：{@code "学生查课程"} 和 {@code "课程查学生"} 分数一样</li>
 *   <li><b>长短不敏感</b>：{@code "薪资"} 和 {@code "平均薪资按专业分组"} 里"薪资"权重相同</li>
 * </ul>
 * 这三条正是向量检索要解决的问题。
 */
@Component
public class KeywordSchemaRetriever implements SchemaRetriever {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 命中位置权重。
     *
     * <p>表名和表注释命中记 3 分，字段级命中记 1 分。这不是拍脑袋——
     * 用户问「专业」，命中 {@code major} 的表注释（"专业表，一个学院下设多个专业"）
     * 显然比命中 {@code college} 的某个字段注释更相关。
     * 拍平成一张大表打分，会让这两者得分相同，排序全靠字母序。
     */
    private static final double HEADER_WEIGHT = 3.0;
    private static final double BODY_WEIGHT = 1.0;
    private static final double MAX_PER_TOKEN = HEADER_WEIGHT + BODY_WEIGHT;

    private final SchemaStore store;

    public KeywordSchemaRetriever(SchemaStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<ScoredTable> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<SchemaDocument> docs = store.loadAll();
        if (docs.isEmpty()) {
            return List.of();
        }

        Set<String> tokens = tokenize(question);
        if (tokens.isEmpty()) {
            return List.of();
        }

        String lowerQuestion = question.toLowerCase();
        List<SchemaDocument> candidates = new ArrayList<>(docs.size());

        // ── IDF：词越稀有，区分度越高 ──────────────────────────────────
        // "学生"出现在 6 张表里，"绩点"只出现在 1 张表里。
        // 如果两者权重相同，问"学生的平均绩点"时 course_enrollment（表注释含"学生"）
        // 会和 student 打平——这显然不对。IDF 就是用来解决这个的。
        java.util.Map<String, Integer> docFreq = new java.util.HashMap<>();
        for (SchemaDocument doc : docs) {
            String all = doc.headerOrEmpty() + " " + doc.searchableOrEmpty();
            for (String token : tokens) {
                if (all.contains(token)) {
                    docFreq.merge(token, 1, Integer::sum);
                }
            }
        }
        int n = docs.size();
        java.util.Map<String, Double> idf = new java.util.HashMap<>();
        double totalIdf = 0;
        for (String token : tokens) {
            int df = docFreq.getOrDefault(token, 0);
            double v = Math.log(1.0 + (double) n / (1.0 + df));
            idf.put(token, v);
            totalIdf += v;
        }
        if (totalIdf <= 0) {
            return List.of();
        }

        List<ScoredTable> scored = new ArrayList<>(docs.size());

        for (SchemaDocument doc : docs) {
            String header = doc.headerOrEmpty();
            String body = doc.searchableOrEmpty();

            List<String> headerHits = new ArrayList<>();
            List<String> bodyHits = new ArrayList<>();
            double weighted = 0;

            for (String token : tokens) {
                boolean inHeader = header.contains(token);
                boolean inBody = body.contains(token);
                if (!inHeader && !inBody) {
                    continue;
                }
                double w = idf.get(token);
                if (inHeader) {
                    headerHits.add(token);
                    weighted += w * HEADER_WEIGHT;
                }
                if (inBody) {
                    bodyHits.add(token);
                    weighted += w * BODY_WEIGHT;
                }
            }
            if (headerHits.isEmpty() && bodyHits.isEmpty()) {
                continue;
            }

            // 归一化到 0~10：每个词最多贡献 (3+1)×idf
            double score = 10.0 * weighted / (MAX_PER_TOKEN * totalIdf);

            // 表名直接出现在问题里，几乎可以确定就是它，给显著加成
            if (lowerQuestion.contains(doc.table().toLowerCase())) {
                score += 5;
            }

            scored.add(new ScoredTable(doc.table(), round(score), describe(headerHits, bodyHits)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredTable::score).reversed()
                        // 同分时按表名排序，保证结果稳定可复现——
                        // 不稳定的排序会让评估结果每次跑都不一样，无法对比
                        .thenComparing(ScoredTable::table))
                .limit(Math.max(1, topK))
                .toList();
    }

    private static String describe(List<String> headerHits, List<String> bodyHits) {
        StringBuilder sb = new StringBuilder();
        if (!headerHits.isEmpty()) {
            sb.append("表级命中: ").append(String.join("、", headerHits));
        }
        if (!bodyHits.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("字段级命中: ").append(String.join("、", bodyHits));
        }
        return sb.toString();
    }

    /**
     * 把问题切成检索词：ASCII 按词切，中文按 2-gram 切。
     */
    Set<String> tokenize(String question) {
        Set<String> tokens = new LinkedHashSet<>();

        Matcher ascii = ASCII_WORD.matcher(question);
        while (ascii.find()) {
            tokens.add(ascii.group().toLowerCase());
        }

        Matcher cjk = CJK_RUN.matcher(question);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() <= 2) {
                tokens.add(run);
            } else {
                for (int i = 0; i + 2 <= run.length(); i++) {
                    tokens.add(run.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
