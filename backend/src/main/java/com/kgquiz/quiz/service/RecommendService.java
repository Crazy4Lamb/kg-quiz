package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.RecommendRule;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.RecommendRuleMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习优先级推荐。
 *
 * <p>原先这套权重（{@code (1-掌握度)×0.7 + 归一化权重×0.3}、前置约束提升、巩固配额）
 * 硬编码在出题引擎里，只为「出题」服务。现在抽成独立、可配置、可解释的规则，
 * <b>同时服务「学什么」（学习任务流）与「练什么」（出题引擎）</b>。</p>
 *
 * <p>出题链路改用 {@link #priorityScore} 与原有公式完全一致，行为不变。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_RULE_NAME = "默认启发式规则";

    private final RecommendRuleMapper recommendRuleMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final GraphStore graphStore;

    /**
     * 规则配置。字段默认值与出题引擎历史硬编码值保持一致，改配置即可调参。
     */
    public record Config(
            double masteryWeight,
            double importanceWeight,
            double weakThreshold,
            double solidThreshold,
            double prereqBoost,
            double consolidateRatio,
            int dailyTaskLimit) {

        public static Config defaults() {
            return new Config(0.7, 0.3, 0.4, 0.7, 0.15, 0.2, 5);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("masteryWeight", masteryWeight);
            m.put("importanceWeight", importanceWeight);
            m.put("weakThreshold", weakThreshold);
            m.put("solidThreshold", solidThreshold);
            m.put("prereqBoost", prereqBoost);
            m.put("consolidateRatio", consolidateRatio);
            m.put("dailyTaskLimit", dailyTaskLimit);
            return m;
        }
    }

    /** 一个知识点的推荐结果：分数 + 可解释理由 */
    public record Recommendation(
            Long nodeId,
            String nodeName,
            String nodeDescription,
            BigDecimal mastery,
            BigDecimal weight,
            String tier,
            double score,
            List<String> reasons) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", nodeId);
            m.put("nodeName", nodeName);
            m.put("nodeDescription", nodeDescription);
            m.put("mastery", mastery);
            m.put("weight", weight);
            m.put("tier", tier);
            m.put("score", Math.round(score * 10000) / 10000.0);
            m.put("reasons", reasons);
            return m;
        }
    }

    // ==================== 规则读写 ====================

    /** 取机构生效的规则：优先机构默认规则 → 平台默认规则 → 内置默认值 */
    public Config configOf(Long orgId) {
        RecommendRule rule = findRule(orgId, true);
        if (rule == null && orgId != null) {
            rule = findRule(null, true);
        }
        if (rule == null) {
            return Config.defaults();
        }
        return parse(rule.getConfig());
    }

    private RecommendRule findRule(Long orgId, boolean enabledOnly) {
        LambdaQueryWrapper<RecommendRule> w = new LambdaQueryWrapper<RecommendRule>()
                .eq(RecommendRule::getIsDefault, 1)
                .orderByAsc(RecommendRule::getId)
                .last("limit 1");
        if (orgId == null) {
            w.isNull(RecommendRule::getOrgId);
        } else {
            w.eq(RecommendRule::getOrgId, orgId);
        }
        if (enabledOnly) {
            w.eq(RecommendRule::getEnabled, 1);
        }
        return recommendRuleMapper.selectOne(w);
    }

    public List<Map<String, Object>> listRules(Long orgId) {
        LambdaQueryWrapper<RecommendRule> w = new LambdaQueryWrapper<RecommendRule>()
                .orderByAsc(RecommendRule::getId);
        Long scope = orgId != null ? orgId : UserContext.scopeOrgId();
        if (scope != null) {
            w.and(q -> q.eq(RecommendRule::getOrgId, scope).or().isNull(RecommendRule::getOrgId));
        }
        List<RecommendRule> rules = recommendRuleMapper.selectList(w);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecommendRule r : rules) {
            out.add(toView(r));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> saveRule(Long id, Long orgId, String name, Map<String, Object> config,
                                        Boolean enabled, Boolean isDefault) {
        Long scope = orgId != null ? orgId : UserContext.scopeOrgId();
        Config parsed = parseMap(config);
        String json;
        try {
            json = MAPPER.writeValueAsString(parsed.toMap());
        } catch (Exception e) {
            throw new BizException("规则配置序列化失败: " + e.getMessage());
        }
        RecommendRule rule;
        if (id != null) {
            rule = recommendRuleMapper.selectById(id);
            if (rule == null) {
                throw new BizException("规则不存在: " + id);
            }
        } else {
            rule = new RecommendRule();
            rule.setOrgId(scope);
            rule.setEnabled(1);
            rule.setIsDefault(0);
        }
        rule.setName(name == null || name.isBlank() ? DEFAULT_RULE_NAME : name.trim());
        rule.setConfig(json);
        if (enabled != null) {
            rule.setEnabled(enabled ? 1 : 0);
        }
        if (isDefault != null) {
            rule.setIsDefault(isDefault ? 1 : 0);
        }
        if (rule.getIsDefault() != null && rule.getIsDefault() == 1) {
            // 同机构只保留一条默认规则
            for (RecommendRule other : recommendRuleMapper.selectList(new LambdaQueryWrapper<RecommendRule>()
                    .eq(RecommendRule::getIsDefault, 1))) {
                boolean sameOrg = (other.getOrgId() == null && rule.getOrgId() == null)
                        || (other.getOrgId() != null && other.getOrgId().equals(rule.getOrgId()));
                if (sameOrg && (rule.getId() == null || !other.getId().equals(rule.getId()))) {
                    other.setIsDefault(0);
                    recommendRuleMapper.updateById(other);
                }
            }
        }
        if (rule.getId() == null) {
            recommendRuleMapper.insert(rule);
        } else {
            recommendRuleMapper.updateById(rule);
        }
        return toView(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        RecommendRule rule = recommendRuleMapper.selectById(id);
        if (rule == null) {
            return;
        }
        if (rule.getOrgId() != null) {
            UserContext.checkOrgScope(rule.getOrgId());
        } else if (UserContext.require().role() != UserRole.ADMIN) {
            // 平台级默认规则是所有机构共用的兜底，只有平台管理员能删
            throw new com.kgquiz.quiz.common.ForbiddenException("平台默认规则仅平台管理员可删除");
        }
        recommendRuleMapper.deleteById(id);
    }

    /** 首次启动补一条平台默认规则，让规则在界面上可见、可调参 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapDefaultRules() {
        try {
            Long count = recommendRuleMapper.selectCount(new LambdaQueryWrapper<RecommendRule>());
            if (count != null && count > 0) {
                return;
            }
            RecommendRule rule = new RecommendRule();
            rule.setOrgId(null);
            rule.setName(DEFAULT_RULE_NAME);
            rule.setIsDefault(1);
            rule.setEnabled(1);
            rule.setConfig(MAPPER.writeValueAsString(Config.defaults().toMap()));
            recommendRuleMapper.insert(rule);
            log.warn("已创建默认推荐规则（平台级）: {}", DEFAULT_RULE_NAME);
        } catch (Exception e) {
            log.warn("初始化默认推荐规则失败: {}", e.getMessage());
        }
    }

    // ==================== 打分 ====================

    /**
     * 优先级分数：缺掌握度 + 知识点重要性。数值越大越该先学。
     * 公式与出题引擎原有实现一致，保证出题行为不变。
     */
    public double priorityScore(double mastery, double weight, double maxWeight, Config cfg) {
        double normalized = maxWeight == 0 ? 1 : weight / maxWeight;
        return (1 - mastery) * cfg.masteryWeight() + normalized * cfg.importanceWeight();
    }

    /**
     * 给学生算推荐学习顺序。
     *
     * @param limit 最多返回多少条，null 用规则里的 dailyTaskLimit
     */
    public List<Recommendation> recommend(Long studentId, Integer limit) {
        StudentGraph sg = latestStudentGraph(studentId);
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱，无法推荐学习内容");
        }
        Long orgId = null;
        var graph = graphStore.findGraph(sg.getSourceGraphId());
        if (graph != null) {
            orgId = graph.getOrgId();
        }
        Config cfg = configOf(orgId);

        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        Map<Long, BigDecimal> masteryMap = new HashMap<>();
        studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .forEach(s -> masteryMap.put(s.getSourceNodeId(),
                        s.getMastery() == null ? BigDecimal.ZERO : s.getMastery()));
        double maxWeight = nodes.stream()
                .map(n -> n.getWeight() == null ? 1.0 : n.getWeight().doubleValue())
                .max(Double::compare).orElse(1.0);

        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (NodeRelation r : graphStore.listRelationsByType(sg.getSourceGraphId(), RelationType.PREREQUISITE)) {
            prereqOf.computeIfAbsent(r.getToNodeId(), k -> new ArrayList<>()).add(r.getFromNodeId());
        }

        Map<Long, GraphNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.getId(), n));

        List<Recommendation> out = new ArrayList<>();
        for (GraphNode node : nodes) {
            BigDecimal mastery = masteryMap.getOrDefault(node.getId(), BigDecimal.ZERO);
            double m = mastery.doubleValue();
            double weight = node.getWeight() == null ? 1.0 : node.getWeight().doubleValue();
            double score = priorityScore(m, weight, maxWeight, cfg);
            List<String> reasons = new ArrayList<>();
            String tier;
            if (m < cfg.weakThreshold()) {
                tier = "WEAK";
                reasons.add("掌握度 " + pct(m) + "，低于薄弱线 " + pct(cfg.weakThreshold()));
            } else if (m >= cfg.solidThreshold()) {
                tier = "SOLID";
                reasons.add("掌握度 " + pct(m) + "，已达掌握线，转为巩固复习");
            } else {
                tier = "MID";
                reasons.add("掌握度 " + pct(m) + "，处于提升区间");
            }
            // 前置约束：自己薄弱且是其他薄弱知识点的前置 → 加权提升
            boolean isPrereqOfWeak = false;
            for (Map.Entry<Long, List<Long>> e : prereqOf.entrySet()) {
                if (!e.getValue().contains(node.getId())) {
                    continue;
                }
                BigDecimal childMastery = masteryMap.getOrDefault(e.getKey(), BigDecimal.ZERO);
                if (childMastery.doubleValue() < cfg.weakThreshold()) {
                    isPrereqOfWeak = true;
                    GraphNode child = byId.get(e.getKey());
                    reasons.add("是薄弱知识点「" + (child == null ? e.getKey() : child.getName()) + "」的前置");
                }
            }
            if (isPrereqOfWeak) {
                score += cfg.prereqBoost();
            }
            if (weight >= 1.5) {
                reasons.add("本册重点（重要性权重 " + trim(weight) + "）");
            }
            out.add(new Recommendation(node.getId(), node.getName(), node.getDescription(),
                    mastery, node.getWeight(), tier, score, reasons));
        }

        // 排序：薄弱优先 → 分数降序；已掌握的沉到最后
        Map<String, Integer> tierRank = Map.of("WEAK", 0, "MID", 1, "SOLID", 2);
        out.sort(Comparator
                .comparingInt((Recommendation r) -> tierRank.getOrDefault(r.tier(), 3))
                .thenComparing(Comparator.comparingDouble(Recommendation::score).reversed()));

        int max = limit != null && limit > 0 ? limit : cfg.dailyTaskLimit();
        return out.size() <= max ? out : out.subList(0, max);
    }

    // ==================== 内部工具 ====================

    private StudentGraph latestStudentGraph(Long studentId) {
        return studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
    }

    private Config parse(String json) {
        if (json == null || json.isBlank()) {
            return Config.defaults();
        }
        try {
            return MAPPER.readValue(json, Config.class);
        } catch (Exception e) {
            log.warn("推荐规则解析失败，回退默认值: {}", e.getMessage());
            return Config.defaults();
        }
    }

    private Config parseMap(Map<String, Object> map) {
        Config def = Config.defaults();
        if (map == null || map.isEmpty()) {
            return def;
        }
        return new Config(
                num(map, "masteryWeight", def.masteryWeight()),
                num(map, "importanceWeight", def.importanceWeight()),
                num(map, "weakThreshold", def.weakThreshold()),
                num(map, "solidThreshold", def.solidThreshold()),
                num(map, "prereqBoost", def.prereqBoost()),
                num(map, "consolidateRatio", def.consolidateRatio()),
                (int) Math.round(num(map, "dailyTaskLimit", def.dailyTaskLimit())));
    }

    private static double num(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Map<String, Object> toView(RecommendRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rule.getId());
        m.put("orgId", rule.getOrgId());
        m.put("name", rule.getName());
        m.put("isDefault", rule.getIsDefault());
        m.put("enabled", rule.getEnabled());
        m.put("config", rule.getConfig());
        m.put("configMap", parse(rule.getConfig()).toMap());
        m.put("createdAt", rule.getCreatedAt());
        m.put("updatedAt", rule.getUpdatedAt());
        return m;
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
