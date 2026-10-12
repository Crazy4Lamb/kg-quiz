package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识点上下文：把「一个知识点在知识图谱里的位置」拼成结构，
 * 供教学内容生成、题目自动识别等大模型场景复用，避免各处重复写取数逻辑。
 *
 * <p>上下文包含：知识点自身、所属图谱、前置 / 后置知识点、包含的子知识点、以及已关联的题目。</p>
 */
@Service
@RequiredArgsConstructor
public class NodeContextService {

    private final GraphStore graphStore;
    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;

    public record NodeContext(
            GraphNode node,
            Graph graph,
            List<GraphNode> prerequisites,
            List<GraphNode> successors,
            List<GraphNode> siblings,
            List<Question> questions) {

        /** 结构化成 Map，方便直接回给前端或塞进提示词 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", node.getId());
            m.put("nodeName", node.getName());
            m.put("description", node.getDescription());
            m.put("code", node.getCode());
            m.put("weight", node.getWeight());
            m.put("graphId", graph == null ? null : graph.getId());
            m.put("graphName", graph == null ? null : graph.getName());
            m.put("subject", graph == null ? null : graph.getSubject());
            m.put("textbook", graph == null ? null : graph.getTextbook());
            m.put("grade", graph == null ? null : graph.getGrade());
            m.put("term", graph == null ? null : graph.getTerm());
            m.put("prerequisites", prerequisites.stream().map(GraphNode::getName).toList());
            m.put("successors", successors.stream().map(GraphNode::getName).toList());
            m.put("siblings", siblings.stream().map(GraphNode::getName).toList());
            m.put("questionCount", questions.size());
            return m;
        }
    }

    /** 取知识点上下文；node 不存在时抛业务异常 */
    public NodeContext of(Long nodeId) {
        GraphNode node = graphStore.findNode(nodeId);
        if (node == null) {
            throw new com.kgquiz.quiz.common.BizException("知识点不存在: " + nodeId);
        }
        Graph graph = node.getGraphId() == null ? null : graphStore.findGraph(node.getGraphId());

        List<GraphNode> prerequisites = new ArrayList<>();
        List<GraphNode> successors = new ArrayList<>();
        List<GraphNode> siblings = new ArrayList<>();
        if (node.getGraphId() != null) {
            for (NodeRelation r : graphStore.listRelations(node.getGraphId())) {
                if (RelationType.PREREQUISITE.equals(r.getRelationType())) {
                    // 前置 → 后置：入边是前置，出边是后置
                    if (nodeId.equals(r.getToNodeId())) {
                        addIfPresent(prerequisites, graphStore.findNode(r.getFromNodeId()));
                    } else if (nodeId.equals(r.getFromNodeId())) {
                        addIfPresent(successors, graphStore.findNode(r.getToNodeId()));
                    }
                } else if (RelationType.CONTAINS.equals(r.getRelationType())) {
                    if (nodeId.equals(r.getFromNodeId())) {
                        // 父 → 子：子节点即本知识点的下级
                        addIfPresent(siblings, graphStore.findNode(r.getToNodeId()));
                    } else if (nodeId.equals(r.getToNodeId())) {
                        addIfPresent(siblings, graphStore.findNode(r.getFromNodeId()));
                    }
                } else if (RelationType.SIMILAR.equals(r.getRelationType())) {
                    if (nodeId.equals(r.getFromNodeId())) {
                        addIfPresent(siblings, graphStore.findNode(r.getToNodeId()));
                    } else if (nodeId.equals(r.getToNodeId())) {
                        addIfPresent(siblings, graphStore.findNode(r.getFromNodeId()));
                    }
                }
            }
        }

        List<Long> questionIds = questionNodeMapper.selectList(
                        new LambdaQueryWrapper<QuestionNode>().eq(QuestionNode::getNodeId, nodeId))
                .stream().map(QuestionNode::getQuestionId).toList();
        List<Question> questions = questionIds.isEmpty() ? List.of() : questionMapper.selectBatchIds(questionIds);

        return new NodeContext(node, graph, prerequisites, successors, siblings, questions);
    }

    /**
     * 把上下文渲染成一段中文说明，直接作为提示词的「知识背景」段落。
     */
    public String describe(NodeContext ctx) {
        StringBuilder sb = new StringBuilder();
        GraphNode node = ctx.node();
        if (ctx.graph() != null) {
            sb.append("学科：").append(nz(ctx.graph().getSubject()))
                    .append("；教材：").append(nz(ctx.graph().getTextbook()))
                    .append("；年级：").append(nz(ctx.graph().getGrade()))
                    .append("；学期：").append(nz(ctx.graph().getTerm())).append("\n");
        }
        sb.append("知识点：").append(node.getName()).append("\n");
        if (notBlank(node.getCode())) {
            sb.append("知识点编码：").append(node.getCode()).append("\n");
        }
        if (notBlank(node.getDescription())) {
            sb.append("知识点说明：").append(node.getDescription()).append("\n");
        }
        if (!ctx.prerequisites().isEmpty()) {
            sb.append("前置知识点（学过才能学本知识点）：")
                    .append(joinNames(ctx.prerequisites())).append("\n");
        }
        if (!ctx.successors().isEmpty()) {
            sb.append("后续知识点：").append(joinNames(ctx.successors())).append("\n");
        }
        if (!ctx.siblings().isEmpty()) {
            sb.append("相关 / 同类知识点：").append(joinNames(ctx.siblings())).append("\n");
        }
        if (!ctx.questions().isEmpty()) {
            sb.append("已有配套题目示例：\n");
            int limit = Math.min(3, ctx.questions().size());
            for (int i = 0; i < limit; i++) {
                Question q = ctx.questions().get(i);
                sb.append("  ").append(i + 1).append(". ").append(oneLine(q.getStem()))
                        .append("（答案：").append(oneLine(q.getAnswer())).append("）\n");
            }
        }
        return sb.toString();
    }

    private static void addIfPresent(List<GraphNode> list, GraphNode node) {
        if (node != null && list.stream().noneMatch(n -> n.getId().equals(node.getId()))) {
            list.add(node);
        }
    }

    private static String joinNames(List<GraphNode> nodes) {
        return nodes.stream().map(GraphNode::getName).reduce((a, b) -> a + "、 " + b).orElse("");
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
