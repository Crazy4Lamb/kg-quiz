package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图分析服务。
 *
 * <p><b>数据来源</b>：图的节点与关系存在 Neo4j，但图算法仍留在应用层内存里跑 ——
 * 图谱规模是几十到几百节点，全量拉取 + 内存计算足够快，也便于保留 Kahn 拓扑分层等
 * 精确的层内排序语义。取数已经收敛到 {@link GraphService}（此前这里绕过它直连 Mapper）。</p>
 *
 * <p>提供三类能力：</p>
 * <ol>
 *   <li>学习路径推荐（前置闭包 + 拓扑分层）</li>
 *   <li>前置缺口分析（递归找未掌握前置）</li>
 *   <li>核心知识点排序（PageRank，可按节点权重个性化）</li>
 * </ol>
 *
 * <p>若将来图谱涨到万级节点，前置闭包可以直接下推成 Cypher 的
 * {@code -[:PREREQUISITE*1..5]->} 变长路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphAnalysisService {

    private final GraphService graphService;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final KgProperties kg;

    // ---------------- 内存图 ----------------

    /** 一次加载图谱到内存，避免 N 次递归查询 */
    private static final class MemGraph {
        Map<Long, GraphNode> nodes = new HashMap<>();
        /** 节点 -> 其前置节点（我对谁有依赖） */
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        /** 节点 -> 依赖它的节点（我支撑谁） */
        Map<Long, List<Long>> supports = new HashMap<>();
        /** 包含关系：父 -> 子 */
        Map<Long, List<Long>> contains = new HashMap<>();
    }

    private MemGraph load(Long graphId) {
        MemGraph g = new MemGraph();
        List<GraphNode> nodes = graphService.listNodes(graphId);
        if (nodes.isEmpty()) {
            throw new BizException("该图谱暂无知识点节点");
        }
        nodes.forEach(n -> g.nodes.put(n.getId(), n));

        List<NodeRelation> relations = graphService.listRelations(graphId);
        for (NodeRelation r : relations) {
            if (RelationType.PREREQUISITE.equals(r.getRelationType())) {
                // from 是前置，to 是后置：to 依赖 from
                g.prereqOf.computeIfAbsent(r.getToNodeId(), k -> new ArrayList<>()).add(r.getFromNodeId());
                g.supports.computeIfAbsent(r.getFromNodeId(), k -> new ArrayList<>()).add(r.getToNodeId());
            } else if (RelationType.CONTAINS.equals(r.getRelationType())) {
                g.contains.computeIfAbsent(r.getFromNodeId(), k -> new ArrayList<>()).add(r.getToNodeId());
            }
        }
        return g;
    }

    /** 前置闭包：nodeId 及其所有（传递）前置节点；环安全 */
    private Set<Long> prereqClosure(MemGraph g, Long nodeId) {
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(nodeId);
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (!visited.add(cur)) {
                continue;
            }
            for (Long pre : g.prereqOf.getOrDefault(cur, List.of())) {
                if (!visited.contains(pre)) {
                    stack.push(pre);
                }
            }
        }
        return visited;
    }

    /**
     * 对给定节点子集按前置依赖做层级划分（Kahn 拓扑排序的层级版本）
     * 第 0 层 = 集合内无前置依赖的节点（最先学）
     * 依赖不在集合内的前置视为已满足；存在环时剩余节点归入最后一层，不抛异常
     */
    private List<List<Long>> topoLayers(MemGraph g, Set<Long> subset) {
        Map<Long, Integer> indeg = new HashMap<>();
        Map<Long, List<Long>> nextInSet = new HashMap<>();
        for (Long id : subset) {
            int d = 0;
            List<Long> nexts = new ArrayList<>();
            for (Long pre : g.prereqOf.getOrDefault(id, List.of())) {
                if (subset.contains(pre)) {
                    d++;
                    nextInSet.computeIfAbsent(pre, k -> new ArrayList<>()).add(id);
                }
            }
            indeg.put(id, d);
        }
        List<List<Long>> layers = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        List<Long> current = subset.stream().filter(id -> indeg.get(id) == 0)
                .sorted(Comparator.comparing(id -> g.nodes.get(id).getSortOrder() == null ? 0
                        : g.nodes.get(id).getSortOrder()))
                .toList();
        while (!current.isEmpty()) {
            layers.add(current);
            done.addAll(current);
            List<Long> next = new ArrayList<>();
            for (Long id : current) {
                for (Long nx : nextInSet.getOrDefault(id, List.of())) {
                    indeg.put(nx, indeg.get(nx) - 1);
                    if (indeg.get(nx) == 0 && !done.contains(nx)) {
                        next.add(nx);
                    }
                }
            }
            current = next;
        }
        // 环中的剩余节点（拓扑无法覆盖）兜底归入最后一层
        List<Long> leftover = subset.stream().filter(id -> !done.contains(id)).toList();
        if (!leftover.isEmpty()) {
            layers.add(new ArrayList<>(leftover));
            log.warn("前置关系存在环，{} 个节点无法拓扑排序，已兜底处理", leftover.size());
        }
        return layers;
    }

    // ---------------- 1. 核心知识点排序（PageRank） ----------------

    /**
     * PageRank：沿"依赖方向"传播权重
     * 若 B 依赖 A（A 是 B 的前置），则 A 被 B 引用 → A 获得 B 的权重传递
     * weightBias=true 时用节点 weight 作为个性化向量（重要知识点更吃重）
     */
    public Map<String, Object> pageRank(Long graphId, boolean weightBias, int topN) {
        MemGraph g = load(graphId);
        List<Long> ids = new ArrayList<>(g.nodes.keySet());
        int n = ids.size();

        // 出边：cur -> 其前置（引用方向）
        Map<Long, List<Long>> out = new HashMap<>();
        for (Long id : ids) {
            List<Long> outs = new ArrayList<>();
            for (Long pre : g.prereqOf.getOrDefault(id, List.of())) {
                if (g.nodes.containsKey(pre)) {
                    outs.add(pre);
                }
            }
            out.put(id, outs);
        }

        double d = 0.85;
        double[] teleport = new double[n];
        Map<Long, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            idx.put(ids.get(i), i);
        }
        if (weightBias) {
            double sum = 0;
            for (Long id : ids) {
                GraphNode node = g.nodes.get(id);
                sum += node.getWeight() == null ? 1.0 : node.getWeight().doubleValue();
            }
            for (int i = 0; i < n; i++) {
                GraphNode node = g.nodes.get(ids.get(i));
                double w = node.getWeight() == null ? 1.0 : node.getWeight().doubleValue();
                teleport[i] = sum == 0 ? 1.0 / n : w / sum;
            }
        } else {
            for (int i = 0; i < n; i++) {
                teleport[i] = 1.0 / n;
            }
        }

        double[] pr = teleport.clone();
        double[] next = new double[n];
        for (int iter = 0; iter < 40; iter++) {
            double dangling = 0;
            for (int i = 0; i < n; i++) {
                if (out.get(ids.get(i)).isEmpty()) {
                    dangling += pr[i];
                }
            }
            for (int i = 0; i < n; i++) {
                next[i] = (1 - d) * teleport[i] + d * (dangling * teleport[i]);
            }
            for (int i = 0; i < n; i++) {
                List<Long> outs = out.get(ids.get(i));
                if (outs.isEmpty()) {
                    continue;
                }
                double share = d * pr[i] / outs.size();
                for (Long target : outs) {
                    next[idx.get(target)] += share;
                }
            }
            double diff = 0;
            for (int i = 0; i < n; i++) {
                diff += Math.abs(next[i] - pr[i]);
            }
            System.arraycopy(next, 0, pr, 0, n);
            if (diff < 1e-8) {
                break;
            }
        }

        List<Map<String, Object>> ranking = new ArrayList<>();
        double max = 0;
        for (double v : pr) {
            max = Math.max(max, v);
        }
        for (int i = 0; i < n; i++) {
            GraphNode node = g.nodes.get(ids.get(i));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.getId());
            item.put("name", node.getName());
            item.put("weight", node.getWeight());
            item.put("pageRank", round6(pr[i]));
            item.put("score", max == 0 ? 0 : round4(pr[i] / max));
            item.put("supportCount", g.supports.getOrDefault(node.getId(), List.of()).size());
            item.put("prereqCount", g.prereqOf.getOrDefault(node.getId(), List.of()).size());
            ranking.add(item);
        }
        ranking.sort((a, b) -> Double.compare((Double) b.get("pageRank"), (Double) a.get("pageRank")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graphId", graphId);
        result.put("nodeCount", n);
        result.put("damping", d);
        result.put("weightBias", weightBias);
        result.put("ranking", ranking.subList(0, Math.min(topN <= 0 ? n : topN, ranking.size())));
        return result;
    }

    // ---------------- 2. 学习路径推荐 ----------------

    /**
     * 学习路径推荐：目标知识点的前置闭包 → 去掉已掌握 → 按依赖层级排序
     */
    public Map<String, Object> recommendPath(Long studentId, Long targetNodeId) {
        StudentCtx ctx = loadStudentCtx(studentId);
        MemGraph g = load(ctx.graphId);
        if (!g.nodes.containsKey(targetNodeId)) {
            throw new BizException("目标知识点不在该学生的图谱中: " + targetNodeId);
        }
        Set<Long> closure = prereqClosure(g, targetNodeId);
        List<List<Long>> layers = topoLayers(g, closure);

        double solidTh = kg.getExam().getSolidThreshold();
        double weakTh = kg.getExam().getWeakThreshold();

        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> mastered = new ArrayList<>();
        int stepNo = 0;
        for (int li = 0; li < layers.size(); li++) {
            for (Long id : layers.get(li)) {
                GraphNode node = g.nodes.get(id);
                double mastery = ctx.mastery.getOrDefault(id, 0.0);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", id);
                item.put("name", node.getName());
                item.put("weight", node.getWeight());
                item.put("mastery", round4(mastery));
                item.put("layer", li);
                item.put("isTarget", id.equals(targetNodeId));
                if (mastery >= solidTh) {
                    item.put("status", "已掌握，可跳过");
                    mastered.add(item);
                } else {
                    item.put("order", ++stepNo);
                    item.put("status", mastery < weakTh ? "薄弱，需重点补" : "掌握中，需巩固");
                    steps.add(item);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("targetNodeId", targetNodeId);
        result.put("targetNodeName", g.nodes.get(targetNodeId).getName());
        result.put("totalClosure", closure.size());
        result.put("steps", steps);
        result.put("skippable", mastered);
        result.put("note", "路径已按前置依赖分层排序（层内可并行学习），已掌握（≥" +
                String.format("%.0f%%", solidTh * 100) + "）的知识点自动跳过");
        return result;
    }

    // ---------------- 3. 前置缺口分析 ----------------

    /**
     * 前置缺口分析：递归找出目标的全部前置中未掌握的部分，按层级从底层往上排
     */
    public Map<String, Object> gapAnalysis(Long studentId, Long nodeId) {
        StudentCtx ctx = loadStudentCtx(studentId);
        MemGraph g = load(ctx.graphId);
        if (!g.nodes.containsKey(nodeId)) {
            throw new BizException("知识点不在该学生的图谱中: " + nodeId);
        }
        Set<Long> closure = prereqClosure(g, nodeId);
        List<List<Long>> layers = topoLayers(g, closure);
        double weakTh = kg.getExam().getWeakThreshold();

        List<Map<String, Object>> gaps = new ArrayList<>();
        int totalGap = 0;
        for (int li = 0; li < layers.size(); li++) {
            List<Map<String, Object>> layerGaps = new ArrayList<>();
            for (Long id : layers.get(li)) {
                double mastery = ctx.mastery.getOrDefault(id, 0.0);
                if (mastery >= weakTh) {
                    continue;
                }
                GraphNode node = g.nodes.get(id);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", id);
                item.put("name", node.getName());
                item.put("mastery", round4(mastery));
                item.put("weight", node.getWeight());
                item.put("layer", li);
                item.put("isSelf", id.equals(nodeId));
                item.put("gapLevel", mastery <= 0.01 ? "完全未掌握"
                        : mastery < weakTh / 2 ? "严重薄弱" : "薄弱");
                layerGaps.add(item);
                totalGap++;
            }
            if (!layerGaps.isEmpty()) {
                Map<String, Object> layerItem = new LinkedHashMap<>();
                layerItem.put("layer", li);
                layerItem.put("nodeCount", layerGaps.size());
                layerItem.put("nodes", layerGaps);
                gaps.add(layerItem);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("nodeId", nodeId);
        result.put("nodeName", g.nodes.get(nodeId).getName());
        result.put("weakThreshold", weakTh);
        result.put("closureSize", closure.size());
        result.put("gapCount", totalGap);
        result.put("layers", gaps);
        result.put("note", gaps.isEmpty()
                ? "该知识点及其全部前置均已掌握（≥" + String.format("%.0f%%", weakTh * 100) + "），无缺口"
                : "建议按层从下往上依次补齐（下层是上层的前置基础）");
        return result;
    }

    // ---------------- 内部 ----------------

    /** 学生上下文：图谱 id + 各节点掌握度 */
    private static final class StudentCtx {
        Long graphId;
        Map<Long, Double> mastery = new HashMap<>();
    }

    private StudentCtx loadStudentCtx(Long studentId) {
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱");
        }
        StudentCtx ctx = new StudentCtx();
        ctx.graphId = sg.getSourceGraphId();
        studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .forEach(s -> ctx.mastery.put(s.getSourceNodeId(),
                        s.getMastery() == null ? 0.0 : s.getMastery().doubleValue()));
        return ctx;
    }

    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private double round6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
