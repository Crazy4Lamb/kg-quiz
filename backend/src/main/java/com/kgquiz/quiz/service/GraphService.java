package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.dto.GraphCopyReq;
import com.kgquiz.quiz.dto.GraphCreateReq;
import com.kgquiz.quiz.dto.NodeBatchReq;
import com.kgquiz.quiz.dto.NodeCreateReq;
import com.kgquiz.quiz.dto.NodeReq;
import com.kgquiz.quiz.dto.PositionReq;
import com.kgquiz.quiz.dto.RelationReq;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱服务：**图操作的唯一入口**。
 *
 * <p>图域（图谱 / 节点 / 关系）存在 Neo4j，通过 {@link GraphStore} 访问；
 * 题目关联（{@code question_node}）与学生掌握度（{@code student_node_state}）仍在 MySQL，
 * 因此删除图谱/节点时要跨库级联，这里用 MySQL 侧先删、Neo4j 侧后删的顺序 ——
 * Neo4j 抛错会让 MySQL 事务回滚，两边保持一致。</p>
 */
@Service
@RequiredArgsConstructor
public class GraphService {

    private final GraphStore graphStore;
    private final QuestionNodeMapper questionNodeMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final VectorIndexService vectorIndexService;

    private static final Set<String> VALID_RELATIONS = Set.of(
            RelationType.CONTAINS, RelationType.SIMILAR, RelationType.PREREQUISITE);

    // ---------------- 图谱 ----------------

    public List<Graph> list(String subject, String textbook, String grade, String term) {
        return graphStore.listGraphs(UserContext.scopeOrgId(), subject, textbook, grade, term);
    }

    public Graph create(GraphCreateReq req) {
        Long orgId = UserContext.resolveWriteOrgId(req.orgId());
        if (graphStore.graphDimensionExists(orgId, req.subject(), req.textbook(), req.grade(), req.term())) {
            throw new BizException("该学科/教材/年级/学期组合已存在图谱");
        }
        Graph graph = new Graph();
        graph.setOrgId(orgId);
        graph.setName(req.name());
        graph.setSubject(req.subject());
        graph.setTextbook(req.textbook());
        graph.setGrade(req.grade());
        graph.setTerm(req.term());
        graph.setDescription(req.description() == null ? "" : req.description());
        return graphStore.insertGraph(graph);
    }

    /** 取图谱并校验机构归属：跨机构访问一律 403 */
    public Graph getById(Long id) {
        Graph graph = graphStore.findGraph(id);
        if (graph == null) {
            throw new BizException("图谱不存在: " + id);
        }
        UserContext.checkOrgScope(graph.getOrgId());
        return graph;
    }

    public Graph update(Long id, GraphCreateReq req) {
        Graph graph = getById(id);
        graph.setName(req.name());
        graph.setDescription(req.description() == null ? "" : req.description());
        graphStore.updateGraph(graph);
        return graph;
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        List<GraphNode> nodes = listNodes(id);
        // MySQL 侧级联：题目关联 + 学生掌握状态
        for (GraphNode node : nodes) {
            questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                    .eq(QuestionNode::getNodeId, node.getId()));
            studentNodeStateMapper.delete(new LambdaQueryWrapper<StudentNodeState>()
                    .eq(StudentNodeState::getSourceNodeId, node.getId()));
        }
        // Neo4j 侧：节点（DETACH DELETE 连带关系）+ 图谱本体
        graphStore.deleteGraph(id);
        for (GraphNode node : nodes) {
            vectorIndexService.deleteNode(node.getId());
        }
    }

    /**
     * 复制图谱：以 {@code sourceId} 为模板，把全部节点（含坐标/权重/编码）与关系原样
     * 复制到新的学科/教材/年级/学期 维度下。用于「新版教材图谱基于旧版改」。
     *
     * <p>题目（question）天然按 学科+教材 归属，不随图谱复制，故不复制。</p>
     */
    @Transactional
    public Map<String, Object> copy(Long sourceId, GraphCopyReq req) {
        // 复制只发生在机构内部：目标图谱沿用源图谱的机构
        Graph source = getById(sourceId);
        if (graphStore.graphDimensionExists(source.getOrgId(), req.subject(), req.textbook(), req.grade(), req.term())) {
            throw new BizException("该学科/教材/年级/学期组合已存在图谱");
        }

        Graph target = new Graph();
        target.setOrgId(source.getOrgId());
        target.setName(req.name());
        target.setSubject(req.subject());
        target.setTextbook(req.textbook());
        target.setGrade(req.grade());
        target.setTerm(req.term());
        target.setDescription(req.description() == null ? "" : req.description());
        graphStore.insertGraph(target);

        List<GraphNode> sourceNodes = listNodes(sourceId);
        List<GraphNode> copies = new ArrayList<>(sourceNodes.size());
        for (GraphNode src : sourceNodes) {
            GraphNode node = new GraphNode();
            node.setGraphId(target.getId());
            node.setOrgId(target.getOrgId());
            node.setName(src.getName());
            node.setDescription(src.getDescription());
            node.setCode(src.getCode());
            node.setWeight(src.getWeight());
            node.setPosX(src.getPosX());
            node.setPosY(src.getPosY());
            node.setSortOrder(src.getSortOrder());
            copies.add(node);
        }
        graphStore.insertNodes(copies);

        Map<Long, Long> idMap = new HashMap<>();
        for (int i = 0; i < sourceNodes.size(); i++) {
            idMap.put(sourceNodes.get(i).getId(), copies.get(i).getId());
        }

        List<NodeRelation> newRelations = new ArrayList<>();
        for (NodeRelation src : listRelations(sourceId)) {
            Long from = idMap.get(src.getFromNodeId());
            Long to = idMap.get(src.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            NodeRelation relation = new NodeRelation();
            relation.setGraphId(target.getId());
            relation.setFromNodeId(from);
            relation.setToNodeId(to);
            relation.setRelationType(src.getRelationType());
            newRelations.add(relation);
        }
        graphStore.insertRelations(newRelations);

        for (GraphNode node : copies) {
            vectorIndexService.upsertNode(target, node);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.getId());
        result.put("name", target.getName());
        result.put("sourceGraphId", sourceId);
        result.put("nodeCount", copies.size());
        result.put("relationCount", newRelations.size());
        return result;
    }

    /** 图谱详情：基本信息 + 全部节点 + 全部关系（供编辑画布） */
    public Map<String, Object> detail(Long id) {
        Graph graph = getById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("graph", graph);
        result.put("nodes", listNodes(id));
        result.put("relations", listRelations(id));
        return result;
    }

    // ---------------- 节点 ----------------

    public List<GraphNode> listNodes(Long graphId) {
        return graphStore.listNodes(graphId);
    }

    /**
     * 新建节点。若请求中带有 {@code link}，则在同一次事务内把新节点与锚点节点关联起来，
     * 关系方向由 {@code anchorIsSource} 决定（缺省 锚点 → 新节点）。
     */
    @Transactional
    public GraphNode createNode(Long graphId, NodeCreateReq req) {
        Graph graph = getById(graphId);
        GraphNode node = new GraphNode();
        node.setName(req.name());
        node.setDescription(req.description() == null ? "" : req.description());
        node.setCode(req.code() == null ? "" : req.code());
        node.setWeight(req.weight() == null ? BigDecimal.ONE : req.weight());
        node.setPosX(req.posX() == null ? 0 : req.posX());
        node.setPosY(req.posY() == null ? 0 : req.posY());
        node.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        node.setGraphId(graphId);
        node.setOrgId(graph.getOrgId());
        graphStore.insertNode(node);

        if (req.link() != null) {
            boolean anchorIsSource = !Boolean.FALSE.equals(req.link().anchorIsSource());
            Long from = anchorIsSource ? req.link().anchorNodeId() : node.getId();
            Long to = anchorIsSource ? node.getId() : req.link().anchorNodeId();
            createRelation(graphId, new RelationReq(from, to, req.link().relationType()));
        }

        vectorIndexService.upsertNode(graph, node);
        return node;
    }

    @Transactional
    public GraphNode updateNode(Long graphId, Long nodeId, NodeReq req) {
        GraphNode node = getNode(graphId, nodeId);
        applyNodeReq(node, req);
        graphStore.updateNode(node);
        vectorIndexService.upsertNode(getById(graphId), node);
        return node;
    }

    @Transactional
    public void deleteNode(Long graphId, Long nodeId) {
        getNode(graphId, nodeId);
        questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getNodeId, nodeId));
        studentNodeStateMapper.delete(new LambdaQueryWrapper<StudentNodeState>()
                .eq(StudentNodeState::getSourceNodeId, nodeId));
        // 以该节点为端点的关系随 DETACH DELETE 一并清除
        graphStore.deleteNode(nodeId);
        vectorIndexService.deleteNode(nodeId);
    }

    /**
     * 批量导入知识点：按 {@code level}（缩进层级）自动串成 CONTAINS 树。
     *
     * <ul>
     *   <li>同名节点直接跳过（不覆盖已有内容），跳过项在 {@code skipped} 中返回</li>
     *   <li>顶层（level 0）若指定了 {@code parentNodeId}，则挂到该节点之下</li>
     *   <li>坐标为自动排布（按层级横向展开），导入后可在画布上手动拖拽调整</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> batchCreateNodes(Long graphId, NodeBatchReq req) {
        Graph graph = getById(graphId);
        if (req.parentNodeId() != null) {
            getNode(graphId, req.parentNodeId());
        }
        Set<String> existingNames = new HashSet<>();
        for (GraphNode node : listNodes(graphId)) {
            existingNames.add(node.getName());
        }

        // level -> 该层级最近创建的节点在 pending 中的下标
        Map<Integer, Integer> levelCursor = new HashMap<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int row = 0;

        for (NodeBatchReq.Item item : req.items()) {
            String name = item.name() == null ? "" : item.name().trim();
            if (name.isEmpty()) {
                skipped.add(Map.of("name", "(空)", "reason", "名称为空"));
                continue;
            }
            if (!existingNames.add(name)) {
                skipped.add(Map.of("name", name, "reason", "图谱内已存在同名知识点"));
                continue;
            }
            int level = item.level() == null ? 0 : Math.max(0, item.level());

            // 找父节点：优先本层级的上一层，找不到就继续向上找
            Integer parentIndex = null;
            for (int up = level - 1; up >= 0; up--) {
                if (levelCursor.containsKey(up)) {
                    parentIndex = levelCursor.get(up);
                    break;
                }
            }
            Long parentNodeId = parentIndex == null ? req.parentNodeId() : null;

            GraphNode node = new GraphNode();
            node.setGraphId(graphId);
            node.setOrgId(graph.getOrgId());
            node.setName(name);
            node.setDescription(item.description() == null ? "" : item.description());
            node.setCode(item.code() == null ? "" : item.code());
            node.setWeight(item.weight() == null ? BigDecimal.ONE : item.weight());
            node.setPosX(80 + level * 240);
            node.setPosY(90 + row * 70);
            node.setSortOrder(row);
            pending.add(new Pending(node, parentIndex, parentNodeId));
            row++;

            // 进入该层级后，清理比它更深的游标，避免跨分支串线
            levelCursor.keySet().removeIf(k -> k >= level);
            levelCursor.put(level, pending.size() - 1);
        }

        List<GraphNode> nodes = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            nodes.add(p.node());
        }
        graphStore.insertNodes(nodes);

        List<NodeRelation> relations = new ArrayList<>();
        for (Pending p : pending) {
            Long from = p.parentIndex() != null ? pending.get(p.parentIndex()).node().getId() : p.parentNodeId();
            if (from == null) {
                continue;
            }
            NodeRelation relation = new NodeRelation();
            relation.setGraphId(graphId);
            relation.setFromNodeId(from);
            relation.setToNodeId(p.node().getId());
            relation.setRelationType(RelationType.CONTAINS);
            relations.add(relation);
        }
        graphStore.insertRelations(relations);

        for (GraphNode node : nodes) {
            vectorIndexService.upsertNode(graph, node);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", nodes.size());
        result.put("relationCount", relations.size());
        result.put("skipped", skipped);
        return result;
    }

    /** 批量导入的中间态：节点 + 父节点（要么是同一批里的下标，要么是库里已有的 id） */
    private record Pending(GraphNode node, Integer parentIndex, Long parentNodeId) {
    }

    public GraphNode getNode(Long graphId, Long nodeId) {
        GraphNode node = graphStore.findNode(nodeId);
        if (node == null || !graphId.equals(node.getGraphId())) {
            throw new BizException("节点不存在: " + nodeId);
        }
        return node;
    }

    @Transactional
    public void savePositions(Long graphId, PositionReq req) {
        getById(graphId);
        for (PositionReq.PositionItem item : req.items()) {
            getNode(graphId, item.nodeId());
            graphStore.updateNodePosition(item.nodeId(), item.posX(), item.posY());
        }
    }

    private void applyNodeReq(GraphNode node, NodeReq req) {
        node.setName(req.name());
        node.setDescription(req.description() == null ? "" : req.description());
        node.setCode(req.code() == null ? "" : req.code());
        if (req.weight() != null) {
            node.setWeight(req.weight());
        }
        if (req.posX() != null) {
            node.setPosX(req.posX());
        }
        if (req.posY() != null) {
            node.setPosY(req.posY());
        }
        if (req.sortOrder() != null) {
            node.setSortOrder(req.sortOrder());
        }
    }

    // ---------------- 关系 ----------------

    public List<NodeRelation> listRelations(Long graphId) {
        return graphStore.listRelations(graphId);
    }

    @Transactional
    public NodeRelation createRelation(Long graphId, RelationReq req) {
        validateRelation(graphId, req, null);
        NodeRelation relation = new NodeRelation();
        relation.setGraphId(graphId);
        relation.setFromNodeId(req.fromNodeId());
        relation.setToNodeId(req.toNodeId());
        relation.setRelationType(req.relationType());
        return graphStore.insertRelation(relation);
    }

    /**
     * 编辑已有关系：可改关系类型，也可改方向（换 from/to），不必删了重建。
     */
    @Transactional
    public NodeRelation updateRelation(Long graphId, Long relationId, RelationReq req) {
        NodeRelation relation = graphStore.findRelation(relationId);
        if (relation == null || !relation.getGraphId().equals(graphId)) {
            throw new BizException("关系不存在: " + relationId);
        }
        validateRelation(graphId, req, relationId);
        relation.setFromNodeId(req.fromNodeId());
        relation.setToNodeId(req.toNodeId());
        relation.setRelationType(req.relationType());
        graphStore.replaceRelation(relation);
        return relation;
    }

    /**
     * 关系通用校验。{@code excludeRelationId} 为编辑场景下被排除的自身 id，避免自己和自己撞重复。
     */
    private void validateRelation(Long graphId, RelationReq req, Long excludeRelationId) {
        getById(graphId);
        if (!VALID_RELATIONS.contains(req.relationType())) {
            throw new BizException("不支持的关系类型: " + req.relationType()
                    + "（可选 CONTAINS / SIMILAR / PREREQUISITE，后置为 PREREQUISITE 的反向）");
        }
        getNode(graphId, req.fromNodeId());
        getNode(graphId, req.toNodeId());
        if (req.fromNodeId().equals(req.toNodeId())) {
            throw new BizException("不能与自己建立关系");
        }
        // 去重：同方向同类型
        long dup = graphStore.countRelation(graphId, req.fromNodeId(), req.toNodeId(), req.relationType());
        if (excludeRelationId != null && dup > 0) {
            // 被排除的关系就是自己，扣掉它
            NodeRelation self = graphStore.findRelation(excludeRelationId);
            if (self != null && self.getFromNodeId().equals(req.fromNodeId())
                    && self.getToNodeId().equals(req.toNodeId())
                    && self.getRelationType().equals(req.relationType())) {
                dup--;
            }
        }
        if (dup > 0) {
            throw new BizException("该关系已存在");
        }
        // 相近关系为对称，反向也不允许重复
        if (RelationType.SIMILAR.equals(req.relationType())) {
            long reverse = graphStore.countRelation(graphId, req.toNodeId(), req.fromNodeId(), RelationType.SIMILAR);
            if (excludeRelationId != null && reverse > 0) {
                NodeRelation self = graphStore.findRelation(excludeRelationId);
                if (self != null && self.getFromNodeId().equals(req.toNodeId())
                        && self.getToNodeId().equals(req.fromNodeId())
                        && RelationType.SIMILAR.equals(self.getRelationType())) {
                    reverse--;
                }
            }
            if (reverse > 0) {
                throw new BizException("相反方向的相近关系已存在");
            }
        }
    }

    @Transactional
    public void deleteRelation(Long graphId, Long relationId) {
        NodeRelation relation = graphStore.findRelation(relationId);
        if (relation == null || !relation.getGraphId().equals(graphId)) {
            throw new BizException("关系不存在: " + relationId);
        }
        graphStore.deleteRelation(relationId);
    }
}
