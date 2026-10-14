package com.kgquiz.quiz.service.qdrant;

import com.kgquiz.quiz.config.KgProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant REST 客户端封装（demo 级，不引 gRPC 依赖）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantClient {

    private final RestClient restClient;
    private final KgProperties kgProperties;

    private String base() {
        return kgProperties.getQdrant().getUrl();
    }

    private String collection() {
        return kgProperties.getQdrant().getCollection();
    }

    public boolean enabled() {
        return kgProperties.getQdrant().isEnabled();
    }

    /**
     * 确保 collection 存在（幂等）。
     *
     * <p>历史 bug：直接 PUT 建集合，集合已存在时 Qdrant 返回 409，被当成失败并打
     * 「语义检索不可用」的 WARN —— 但集合其实一直可用，等于每次启动都报假警，
     * 真正的连接故障反而被淹没。这里改成先探测再决定是否创建。</p>
     *
     * @return true = 本次<b>新建</b>了集合（调用方据此决定要不要全量重建索引）
     */
    @SuppressWarnings("unchecked")
    public boolean ensureCollection(int dim) {
        // 1. 先探测：已存在且有数据就跳过
        try {
            Map<String, Object> info = restClient.get()
                    .uri(base() + "/collections/" + collection())
                    .retrieve()
                    .body(Map.class);
            if (info != null && info.get("result") instanceof Map<?, ?> r) {
                Object status = r.get("status");
                long points = r.get("points_count") instanceof Number n ? n.longValue() : -1L;
                if (points == 0) {
                    // 集合在但没有点：容器重启丢过数据（无持久化）时最常见。
                    // 这种状态比「集合不存在」更危险 —— 检索接口照样返回成功，只是永远召回 0 条。
                    log.warn("Qdrant collection {} 存在但为空（0 个向量），需全量重建索引", collection());
                    return true;
                }
                log.info("Qdrant collection {} 已就绪（状态 {}，{} 个向量），跳过创建",
                        collection(), status, points);
                return false;
            }
        } catch (Exception e) {
            log.debug("collection {} 探测未命中，尝试创建: {}", collection(), e.getMessage());
        }

        // 2. 不存在则创建；409 视为并发下别人已建好，同样算就绪
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", dim,
                        "distance", "Cosine"));
        try {
            restClient.put()
                    .uri(base() + "/collections/" + collection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Qdrant collection {} 创建完成，语义检索可用", collection());
            return true;
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("409")) {
                log.info("Qdrant collection {} 已存在（409），语义检索可用", collection());
                return false;
            }
            // 这里才是真的不可用：连不上、鉴权错、维度冲突等
            log.warn("Qdrant collection {} 创建失败，语义检索不可用: {}", collection(), e.getMessage());
            return false;
        }
    }

    /** upsert 单个向量点 */
    public void upsertPoint(long id, List<Float> vector, Map<String, Object> payload) {
        Map<String, Object> body = Map.of(
                "points", List.of(Map.of(
                        "id", id,
                        "vector", vector,
                        "payload", payload)));
        try {
            restClient.put()
                    .uri(base() + "/collections/" + collection() + "/points?wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Qdrant upsert 失败 pointId={}: {}", id, e.getMessage());
        }
    }

    public void deletePoint(long id) {
        try {
            restClient.post()
                    .uri(base() + "/collections/" + collection() + "/points/delete?wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", List.of(id)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Qdrant delete 失败 pointId={}: {}", id, e.getMessage());
        }
    }

    /**
     * 向量检索，返回 [{id, score, payload}]
     * 优先使用新版 query API（Qdrant 1.10+），失败回退旧版 search API
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(List<Float> vector, int limit, Long graphId) {
        Map<String, Object> filter = null;
        if (graphId != null) {
            filter = Map.of(
                    "must", List.of(Map.of(
                            "key", "graphId",
                            "match", Map.of("value", graphId))));
        }

        // 1. 新版 query API
        Map<String, Object> queryBody = new HashMap<>();
        queryBody.put("query", vector);
        queryBody.put("limit", limit);
        queryBody.put("with_payload", true);
        if (filter != null) {
            queryBody.put("filter", filter);
        }
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(base() + "/collections/" + collection() + "/points/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(queryBody)
                    .retrieve()
                    .body(Map.class);
            if (resp != null && resp.get("result") instanceof Map<?, ?> result
                    && result.get("points") instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        } catch (Exception e) {
            log.debug("Qdrant query API 调用失败，回退 search API: {}", e.getMessage());
        }

        // 2. 回退旧版 search API
        Map<String, Object> body = new HashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        if (filter != null) {
            body.put("filter", filter);
        }
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(base() + "/collections/" + collection() + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                return List.of();
            }
            Object result = resp.get("result");
            if (result instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Qdrant search 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 删除 collection（重建索引用） */
    public void dropCollection() {
        try {
            restClient.delete()
                    .uri(base() + "/collections/" + collection())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Qdrant drop 失败: {}", e.getMessage());
        }
    }

    /** 提取 search 命中结果里的 payload，组装统一结构 */
    public List<Map<String, Object>> extractHits(List<Map<String, Object>> raw) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            Map<String, Object> hit = new HashMap<>();
            hit.put("id", item.get("id"));
            hit.put("score", item.get("score"));
            Object payload = item.get("payload");
            if (payload instanceof Map<?, ?> p) {
                hit.putAll((Map<String, Object>) p);
            }
            hits.add(hit);
        }
        return hits;
    }
}
