package com.askbase.schema;

import com.askbase.schema.model.SchemaDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema 索引的持久化。
 *
 * <p>存 Redis 而不是每次现查 information_schema，理由有三：
 * <ol>
 *   <li>采集要跑几十条 SQL（含 COUNT、DISTINCT 采样），每次请求都跑一遍太慢</li>
 *   <li>将来加向量索引，embedding 必须持久化——重算一次要调几十次模型 API，很贵</li>
 *   <li>schema 变更频率极低，缓存收益远大于一致性成本</li>
 * </ol>
 *
 * <p>存储结构（刻意选简单可读的形式，方便用 redis-cli 直接看）：
 * <pre>
 *   askbase:schema:docs   HASH   field=表名  value=SchemaDocument 的 JSON
 *   askbase:schema:meta   STRING 索引元信息（时间、表数、总 token）
 * </pre>
 *
 * <p>⚠️ 这里刻意<b>不</b>用 RediSearch 的向量索引。向量检索在后续实现，
 * 现在先把"文本索引"这条路走通，将来加向量时才有 baseline 可对比。
 */
@Component
public class SchemaStore {

    private static final Logger log = LoggerFactory.getLogger(SchemaStore.class);

    private static final String KEY_DOCS = "askbase:schema:docs";
    private static final String KEY_META = "askbase:schema:meta";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 进程内缓存。schema 文档只有十几条、几 KB，常驻内存完全没压力，
     * 省掉每次检索一次 Redis 往返。save() 时失效。
     */
    private volatile List<SchemaDocument> cache;

    public SchemaStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(List<SchemaDocument> documents) {
        Map<String, String> map = new LinkedHashMap<>();
        int totalTokens = 0;
        for (SchemaDocument d : documents) {
            try {
                map.put(d.table(), objectMapper.writeValueAsString(d));
                totalTokens += d.tokenEstimate();
            } catch (Exception e) {
                log.warn("序列化表 {} 失败：{}", d.table(), e.getMessage());
            }
        }

        redis.delete(KEY_DOCS);
        if (!map.isEmpty()) {
            redis.opsForHash().putAll(KEY_DOCS, map);
        }
        redis.opsForValue().set(KEY_META, toJson(new Meta(
                OffsetDateTime.now().toString(), map.size(), totalTokens)));

        this.cache = List.copyOf(documents);
        log.info("Schema 索引已写入 Redis：{} 张表，约 {} token", map.size(), totalTokens);
    }

    public List<SchemaDocument> loadAll() {
        List<SchemaDocument> local = cache;
        if (local != null) {
            return local;
        }
        try {
            // 显式给 opsForHash 类型参数：不写的话 HK/HV 会被推成 Object，
            // values() 返回 List<Object> 而不是 List<String>
            Map<String, String> entries = redis.<String, String>opsForHash().entries(KEY_DOCS);
            List<SchemaDocument> docs = new ArrayList<>(entries.size());
            for (String json : entries.values()) {
                docs.add(objectMapper.readValue(json, SchemaDocument.class));
            }
            docs.sort((a, b) -> a.table().compareTo(b.table()));
            this.cache = List.copyOf(docs);
            return this.cache;
        } catch (Exception e) {
            // Redis 挂了不应该让应用起不来——降级为空索引，检索返回空
            log.warn("从 Redis 读取 Schema 索引失败（Redis 是否在运行？）：{}", e.getMessage());
            return List.of();
        }
    }

    public boolean isEmpty() {
        return loadAll().isEmpty();
    }

    public Meta meta() {
        try {
            String json = redis.opsForValue().get(KEY_META);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<Meta>() {
                });
            }
        } catch (Exception e) {
            log.debug("读取索引元信息失败：{}", e.getMessage());
        }
        List<SchemaDocument> docs = loadAll();
        return new Meta("(未索引)", docs.size(), docs.stream().mapToInt(SchemaDocument::tokenEstimate).sum());
    }

    public void clear() {
        redis.delete(List.of(KEY_DOCS, KEY_META));
        this.cache = List.of();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 索引元信息 */
    public record Meta(String indexedAt, int tableCount, int totalTokens) {
    }
}
