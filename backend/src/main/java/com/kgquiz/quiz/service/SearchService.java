package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.service.embedding.EmbeddingService;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.service.qdrant.QdrantClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点检索：ID 精确 / 字面（Neo4j CONTAINS）/ Qdrant 语义，多路合并。
 *
 * <p>字面检索从原来的 SQL LIKE 换成 Cypher 的 {@code CONTAINS}，语义一致（都是子串匹配），
 * 只是换了执行引擎。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final GraphStore graphStore;
    private final EmbeddingService embeddingService;
    private final QdrantClient qdrantClient;
    private final VectorIndexService vectorIndexService;

    private static final double SEMANTIC_SCORE_THRESHOLD = 0.30;
    private static final int LITERAL_LIMIT = 200;
    private static final int SEMANTIC_TOPK = 20;

    public Map<String, Object> search(Long nodeId, String keyword, Long graphId, String mode) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Map<Long, Graph> graphCache = new HashMap<>();

        // 1. ID 精确查询
        if (nodeId != null) {
            GraphNode node = graphStore.findNode(nodeId);
            if (node != null && UserContext.inScope(node.getOrgId())
                    && (graphId == null || node.getGraphId().equals(graphId))) {
                hits.add(toHit(node, "id", 1.0, graphCache));
                seen.add(node.getId());
            }
        }

        boolean wantLike = mode == null || "all".equals(mode) || "like".equals(mode);
        boolean wantSemantic = mode == null || "all".equals(mode) || "semantic".equals(mode);

        // 2. 字面匹配（名称 + 描述）
        if (wantLike && keyword != null && !keyword.isBlank()) {
            for (GraphNode node : graphStore.searchNodes(keyword, UserContext.scopeOrgId(), graphId, LITERAL_LIMIT)) {
                if (seen.add(node.getId())) {
                    hits.add(toHit(node, "like", 1.0, graphCache));
                }
            }
        }

        // 3. Qdrant 语义召回
        if (wantSemantic && keyword != null && !keyword.isBlank() && vectorIndexService.available()) {
            try {
                List<Float> vector = embeddingService.embed(keyword);
                List<Map<String, Object>> raw = qdrantClient.search(vector, SEMANTIC_TOPK, graphId);
                for (Map<String, Object> hit : qdrantClient.extractHits(raw)) {
                    double score = hit.get("score") instanceof Number n ? n.doubleValue() : 0;
                    if (score < SEMANTIC_SCORE_THRESHOLD) {
                        continue;
                    }
                    Long id = hit.get("id") instanceof Number n ? n.longValue() : null;
                    if (id == null || seen.contains(id)) {
                        continue;
                    }
                    GraphNode node = graphStore.findNode(id);
                    if (node == null || !UserContext.inScope(node.getOrgId())) {
                        continue;
                    }
                    Map<String, Object> h = toHit(node, "semantic", score, graphCache);
                    h.put("matchedName", hit.get("name"));
                    hits.add(h);
                    seen.add(id);
                }
            } catch (Exception e) {
                log.warn("语义检索失败，仅返回字面结果: {}", e.getMessage());
            }
        }

        result.put("hits", hits);
        result.put("total", hits.size());
        result.put("semanticAvailable", vectorIndexService.available());
        return result;
    }

    private Map<String, Object> toHit(GraphNode node, String source, double score, Map<Long, Graph> graphCache) {
        Map<String, Object> hit = new HashMap<>();
        hit.put("nodeId", node.getId());
        hit.put("graphId", node.getGraphId());
        hit.put("name", node.getName());
        hit.put("description", node.getDescription());
        hit.put("code", node.getCode());
        hit.put("weight", node.getWeight());
        hit.put("source", source);
        hit.put("score", score);
        Graph graph = graphCache.computeIfAbsent(node.getGraphId(), graphStore::findGraph);
        if (graph != null) {
            hit.put("graphName", graph.getName());
            hit.put("subject", graph.getSubject());
            hit.put("textbook", graph.getTextbook());
            hit.put("grade", graph.getGrade());
            hit.put("term", graph.getTerm());
        }
        return hit;
    }
}
