package com.kgquiz.quiz.service;

import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.service.embedding.EmbeddingService;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.service.qdrant.QdrantClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点向量索引同步：节点增删改时同步 Qdrant，提供全量重建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final QdrantClient qdrantClient;
    private final EmbeddingService embeddingService;
    private final GraphStore graphStore;
    private final KgProperties kgProperties;

    /**
     * 启动时确保向量索引就绪。
     *
     * <p>集合不存在、或存在但没有点时，全量重建 —— 「有集合但 0 个点」是最坏状态：
     * {@code available()} 返回 true，语义检索看起来正常，却永远召回 0 条，
     * 问题要到功能上不去才被发现。Qdrant 若挂了 volume（正常部署）重启不丢数据，
     * 不会走到重建分支；丢了也能自愈，因为向量不存 Neo4j，全量重算即可。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!qdrantClient.enabled() || !embeddingService.available()) {
            log.warn("向量索引未启用（Qdrant enabled={}, embedding available={}）",
                    qdrantClient.enabled(), embeddingService.available());
            return;
        }
        boolean needRebuild = qdrantClient.ensureCollection(embeddingService.dim());
        if (needRebuild) {
            log.info("向量索引缺失或为空，启动全量重建（避免召回恒为 0 却看不出异常）");
            try {
                rebuild();
            } catch (Exception e) {
                log.warn("向量索引自动重建失败，语义检索将退化为字面相似度: {}", e.getMessage());
            }
        }
    }

    public boolean available() {
        return qdrantClient.enabled() && embeddingService.available();
    }

    public void upsertNode(Graph graph, GraphNode node) {
        if (!available()) {
            return;
        }
        try {
            String text = node.getName() + (node.getDescription() == null || node.getDescription().isEmpty()
                    ? "" : "\n" + node.getDescription());
            Map<String, Object> payload = new HashMap<>();
            payload.put("nodeId", node.getId());
            payload.put("graphId", graph.getId());
            payload.put("name", node.getName());
            payload.put("description", node.getDescription());
            payload.put("subject", graph.getSubject());
            payload.put("textbook", graph.getTextbook());
            payload.put("grade", graph.getGrade());
            payload.put("term", graph.getTerm());
            qdrantClient.upsertPoint(node.getId(), embeddingService.embed(text), payload);
        } catch (Exception e) {
            log.warn("节点向量同步失败 nodeId={}: {}", node.getId(), e.getMessage());
        }
    }

    public void deleteNode(Long nodeId) {
        if (!available()) {
            return;
        }
        try {
            qdrantClient.deletePoint(nodeId);
        } catch (Exception e) {
            log.warn("节点向量删除失败 nodeId={}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * 全量重建向量索引
     */
    public Map<String, Object> rebuild() {
        if (!available()) {
            throw new IllegalStateException("向量索引不可用，请检查 Qdrant 与 embedding 模型配置");
        }
        qdrantClient.dropCollection();
        qdrantClient.ensureCollection(embeddingService.dim());
        List<Graph> graphs = graphStore.listGraphs(null, null, null, null, null);
        int count = 0;
        for (Graph graph : graphs) {
            List<GraphNode> nodes = graphStore.listNodes(graph.getId());
            for (GraphNode node : nodes) {
                upsertNode(graph, node);
                count++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("indexedNodes", count);
        result.put("graphs", graphs.size());
        log.info("向量索引重建完成: {} nodes / {} graphs", count, graphs.size());
        return result;
    }
}
