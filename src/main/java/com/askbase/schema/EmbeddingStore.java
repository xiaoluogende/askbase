package com.askbase.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 表结构向量的持久化。
 *
 * <p>为什么必须持久化：一次 reindex 要调 10 次 embedding API，每次都有费用和延迟。
 * 存下来之后，重启、改代码都不用重算。
 *
 * <p>存储：{@code askbase:schema:emb} 是一个 Hash，field=表名，value=JSON 数组（1024 个浮点数）。
 *
 * <p>⚠️ 这里<b>没有</b>用 RediSearch 的向量索引，而是在 Java 里算余弦相似度。原因：
 * 只有 10 张表，全量比对是微秒级，上 KNN 索引属于过度设计。
 * 抽成 {@link com.askbase.schema.retriever.SchemaRetriever} 接口后，
 * 将来表数上万时换成 RediSearch KNN 只需要新增一个实现，不动调用方。
 * <b>这个取舍本身是可以在面试里讲的。</b>
 */
@Component
public class EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStore.class);

    private static final String KEY_EMB = "askbase:schema:emb";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private volatile Map<String, float[]> cache;

    public EmbeddingStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(Map<String, float[]> embeddings) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, float[]> e : embeddings.entrySet()) {
            try {
                map.put(e.getKey(), objectMapper.writeValueAsString(e.getValue()));
            } catch (Exception ex) {
                log.warn("序列化 {} 的向量失败：{}", e.getKey(), ex.getMessage());
            }
        }
        redis.delete(KEY_EMB);
        if (!map.isEmpty()) {
            redis.opsForHash().putAll(KEY_EMB, map);
        }
        this.cache = Map.copyOf(embeddings);
        log.info("向量索引已写入 Redis：{} 张表", map.size());
    }

    public Map<String, float[]> loadAll() {
        Map<String, float[]> local = cache;
        if (local != null) {
            return local;
        }
        try {
            Map<String, String> raw = redis.<String, String>opsForHash().entries(KEY_EMB);
            Map<String, float[]> result = new HashMap<>(raw.size());
            for (Map.Entry<String, String> e : raw.entrySet()) {
                result.put(e.getKey(), objectMapper.readValue(e.getValue(), new TypeReference<float[]>() {
                }));
            }
            this.cache = Map.copyOf(result);
            return this.cache;
        } catch (Exception e) {
            log.warn("读取向量索引失败：{}", e.getMessage());
            return Map.of();
        }
    }

    public boolean isEmpty() {
        return loadAll().isEmpty();
    }

    public void clear() {
        redis.delete(KEY_EMB);
        this.cache = Map.of();
    }
}
