package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.RecommendRule;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.mapper.RecommendRuleMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点学习推荐引擎。
 *
 * <p><b>两套打分并存，这是刻意的，不要合并：</b></p>
 * <ul>
 *   <li>{@link #priorityScore} 是<b>出题引擎</b>用的历史公式
 *       {@code (1-掌握度)×0.7 + 归一化重要性×0.3 + 前置加成}。
 *       它回答「这一份卷子该从哪些知识点抽题」，只关心题能不能凑齐。
 *       签名与行为<b>一字不改</b>，保证既有出题结果可复现。</li>
 *   <li>{@link #score} 是<b>推荐引擎</b>用的六因子模型，回答「这个孩子今天该学什么」。
 *       它多了遗忘、置信度、近期重复三个因子，因为「刚练过的知识点该不该再推一遍」
 *       这类问题，出题场景根本不会遇到，但学习推荐天天遇到。</li>
 * </ul>
 *
 * <p>两者共用一份规则配置（{@link Config}）：出题只取它关心的那几个字段，
 * 推荐取全部。<b>配置真源唯一在 {@code recommend_rule} 表</b>。</p>
 *
 * <p>打分 → 策略 → 配额裁剪三段式，理由见 {@link #recommend} 的注释。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_RULE_NAME = "默认启发式规则";

    private final RecommendRuleMapper recommendRuleMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final GraphStore graphStore;
    private final MasteryProfileService masteryProfileService;
    private final LearningRecordService learningRecordService;

    // ==================== 策略 ====================

    /**
     * 推荐策略。
     *
     * <p>策略<b>不改权重，只改「怎么用分数」</b>：补弱优先会把低分项强行提到前面，
     * 顺序推进会沿着前置链一层层往下推，应试优先会把高重要性项钉在最前。
     * 权重是「多想要」，策略是「按谁优先」—— 两件事分开，调参时不会互相打架。</p>
     */
    public enum Strategy {
        REMEDIATION("补弱优先", "先把最不熟的补上来，容忍对重点知识的暂时延后"),
        SEQUENTIAL("顺序推进", "沿前置链逐层往下推，保证不跳过前置造成反复卡壳"),
        EXAM("应试优先", "重点知识点优先，弱项次之，适合临考冲刺"),
        BALANCED("均衡", "六个因子折中排序，日常默认");

        public final String label;
        public final String desc;

        Strategy(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }

        public static Strategy parse(String s) {
            if (s == null || s.isBlank()) {
                return BALANCED;
            }
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return BALANCED;
            }
        }
    }

    /**
     * 规则配置。
     *
     * <p>分四组：权重（想要什么）、阈值（怎么算薄弱/掌握）、遗忘（会忘多少）、
     * 编排（推多少、怎么分配名额）。字段默认值与出题引擎历史硬编码值保持一致，
     * 改配置即可调参。</p>
     */
    public record Config(
            /* ---- 权重组：出题与推荐都吃 ---- */
            double masteryWeight,
            double importanceWeight,
            double weakThreshold,
            double solidThreshold,
            double prereqBoost,
            double consolidateRatio,
            int dailyTaskLimit,
            /* ---- 推荐专属权重组：只有学习推荐用 ---- */
            double forgettingWeight,
            double uncertaintyWeight,
            double repetitionPenalty,
            /* ---- 遗忘与置信度组 ---- */
            double forgettingHalfLifeDays,
            double decayFloorRatio,
            int confidenceFullPractice,
            /* ---- 编排组 ---- */
            double weakQuotaRatio,
            double reviewQuotaRatio,
            int recentRepeatWindowDays,
            int maxPerParent) {

        /** 历史七参构造器，保持既有调用点（ExamService）编译与行为不变 */
        public Config(double masteryWeight, double importanceWeight, double weakThreshold,
                       double solidThreshold, double prereqBoost, double consolidateRatio,
                       int dailyTaskLimit) {
            this(masteryWeight, importanceWeight, weakThreshold, solidThreshold, prereqBoost,
                    consolidateRatio, dailyTaskLimit, 0.2, 0.1, 0.15, 14, 0.6, 4, 0.6, 0.2, 2, 2);
        }

        public static Config defaults() {
            return new Config(0.7, 0.3, 0.4, 0.7, 0.15, 0.2, 5,
                    0.2, 0.1, 0.15, 14, 0.6, 4, 0.6, 0.2, 2, 2);
        }

        /** 该策略推荐的权重预设：给「补弱优先」这类策略一个合理起点，而不是让用户从零猜 */
        public static Config preset(Strategy s) {
            return switch (s) {
                case REMEDIATION -> new Config(0.85, 0.15, 0.45, 0.7, 0.2, 0.15, 5,
                        0.3, 0.15, 0.2, 10, 0.6, 4, 0.75, 0.15, 2, 3);
                case SEQUENTIAL -> new Config(0.5, 0.2, 0.4, 0.7, 0.3, 0.2, 6,
                        0.15, 0.05, 0.1, 21, 0.6, 4, 0.5, 0.2, 3, 2);
                case EXAM -> new Config(0.4, 0.6, 0.35, 0.75, 0.1, 0.3, 8,
                        0.25, 0.05, 0.15, 7, 0.6, 4, 0.5, 0.25, 1, 4);
                case BALANCED -> defaults();
            };
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
            m.put("forgettingWeight", forgettingWeight);
            m.put("uncertaintyWeight", uncertaintyWeight);
            m.put("repetitionPenalty", repetitionPenalty);
            m.put("forgettingHalfLifeDays", forgettingHalfLifeDays);
            m.put("decayFloorRatio", decayFloorRatio);
            m.put("confidenceFullPractice", confidenceFullPractice);
            m.put("weakQuotaRatio", weakQuotaRatio);
            m.put("reviewQuotaRatio", reviewQuotaRatio);
            m.put("recentRepeatWindowDays", recentRepeatWindowDays);
            m.put("maxPerParent", maxPerParent);
            return m;
        }
    }

    /** 参数元数据：前端配置面板靠它动态渲染，不在前端硬编码字段列表 */
    private static final List<Map<String, Object>> PARAM_GROUPS = List.of(
            paramGroup("weight", "打分权重", "决定「多想要」这一类知识点。调这组会同时影响学习推荐与出题顺序", List.of(
                    param("masteryWeight", "掌握缺口权重", 0, 1, 0.05,
                            "分数 = 缺口 × 该权重 + …。越大越偏向「先补最不熟的」"),
                    param("importanceWeight", "知识点重要性权重", 0, 1, 0.05,
                            "越大越偏向「先学本册重点」。两项之和通常为 1"),
                    param("forgettingWeight", "遗忘风险权重", 0, 1, 0.05,
                            "距上次练习越久权重越大。调高能有效减少「学完就忘」"),
                    param("uncertaintyWeight", "低置信度权重", 0, 1, 0.05,
                            "只练过一两次的知识点即使分高，也会优先安排一次验证"),
                    param("repetitionPenalty", "近期重复惩罚", 0, 1, 0.05,
                            "最近几天内学过的会被扣分，避免刚做完的题又排到第一")
            )),
            paramGroup("threshold", "分级阈值", "定义「薄弱 / 入门 / 熟练 / 掌握」的边界，学情报告与推荐共用", List.of(
                    param("weakThreshold", "薄弱线", 0, 1, 0.05, "低于该值判为薄弱，优先安排学习"),
                    param("solidThreshold", "掌握线", 0, 1, 0.05, "高于该值判为已掌握，转为低强度复习"),
                    param("prereqBoost", "前置提升加成", 0, 1, 0.05, "是其他薄弱知识点的前置时，额外加上该值"),
                    param("consolidateRatio", "巩固题配额占比", 0, 0.5, 0.05, "出题时留给已掌握知识点的比例，防止遗忘")
            )),
            paramGroup("memory", "遗忘与置信度", "回答「他现在还记得吗」「这个判断有多可信」", List.of(
                    param("forgettingHalfLifeDays", "遗忘半衰期（天）", 1, 60, 1,
                            "掌握度减半所需的天数。7 天适合临考冲刺，21 天适合长期学习"),
                    param("decayFloorRatio", "衰减下限系数", 0, 1, 0.05,
                            "衰减不会低于「历史峰值 × 该系数」，防止长期不练的知识点永久被判薄弱"),
                    param("confidenceFullPractice", "置信度达标练习次数", 1, 20, 1,
                            "练够这么多次，置信度才接近高。置信度 = 次数 /(次数 + 该值)")
            )),
            paramGroup("orchestration", "推荐编排", "决定推多少、怎么分配名额", List.of(
                    param("dailyTaskLimit", "每日知识点条数", 1, 20, 1, "一次推多少个知识点（每个会生成学+练两条任务）"),
                    param("weakQuotaRatio", "薄弱保底占比", 0, 1, 0.05, "至少这么比例的名额留给薄弱知识点，防止全是复习"),
                    param("reviewQuotaRatio", "复习配额占比", 0, 0.5, 0.05, "留给已掌握知识点的复习名额，防遗忘"),
                    param("recentRepeatWindowDays", "近期重复窗口（天）", 0, 30, 1, "窗口期内学过的知识点会被扣分"),
                    param("maxPerParent", "同一父章节最多推几个", 1, 10, 1, "避免连续推荐同一章下的多个知识点")
            )));

    private static Map<String, Object> paramGroup(String key, String title, String desc, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("desc", desc);
        m.put("params", items);
        return m;
    }

    private static Map<String, Object> param(String key, String label, double min, double max, double step, String tip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("min", min);
        m.put("max", max);
        m.put("step", step);
        m.put("tip", tip);
        return m;
    }

    /** 参数元数据 + 策略清单，回给前端动态渲染配置面板 */
    public Map<String, Object> paramMeta() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", PARAM_GROUPS);
        out.put("defaults", Config.defaults().toMap());
        List<Map<String, Object>> strategies = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", s.name());
            m.put("label", s.label);
            m.put("desc", s.desc);
            m.put("preset", Config.preset(s).toMap());
            strategies.add(m);
        }
        out.put("strategies", strategies);
        return out;
    }

    // ==================== 打分结果 ====================

    /** 单个因子的得分明细，用于把「为什么推荐它」摊开给人看 */
    public record Factor(String key, String label, double value, double weight, double contribution, String note) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("value", Math.round(value * 10000) / 10000.0);
            m.put("weight", Math.round(weight * 10000) / 10000.0);
            m.put("contribution", Math.round(contribution * 10000) / 10000.0);
            m.put("note", note);
            return m;
        }
    }

    /**
     * 一个知识点的推荐结果。
     *
     * @param score     六因子加权总分（含重复惩罚，可能为负）
     * @param tier      分档：WEAK 薄弱 / MID 提升 / SOLID 已掌握 / UNSEEN 未接触
     * @param rawScore  加权总分（<b>不含</b>重复惩罚），用于对比「这孩子本来有多该学」
     * @param factors   分项明细
     */
    public record Recommendation(
            Long nodeId,
            String nodeName,
            String nodeDescription,
            Long parentNodeId,
            BigDecimal mastery,
            double effectiveMastery,
            int practiceCount,
            double confidence,
            int daysSincePractice,
            String level,
            String levelLabel,
            BigDecimal weight,
            String tier,
            double score,
            double rawScore,
            List<Factor> factors,
            List<String> reasons,
            String suggestedAction) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", nodeId);
            m.put("nodeName", nodeName);
            m.put("nodeDescription", nodeDescription);
            m.put("parentNodeId", parentNodeId);
            m.put("mastery", mastery);
            m.put("effectiveMastery", round(effectiveMastery));
            m.put("practiceCount", practiceCount);
            m.put("confidence", round(confidence));
            m.put("daysSincePractice", daysSincePractice);
            m.put("level", level);
            m.put("levelLabel", levelLabel);
            m.put("weight", weight);
            m.put("tier", tier);
            m.put("score", round(score));
            m.put("rawScore", round(rawScore));
            m.put("factors", factors.stream().map(Factor::toMap).toList());
            m.put("reasons", reasons);
            m.put("suggestedAction", suggestedAction);
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

    /** 生效策略：与 {@link #configOf} 同一条解析链，保证配置页显示的策略就是实际生效的 */
    public Strategy strategyOf(Long orgId) {
        RecommendRule rule = findRule(orgId, true);
        if (rule == null && orgId != null) {
            rule = findRule(null, true);
        }
        return Strategy.parse(rule == null ? null : rule.getStrategy());
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
    public Map<String, Object> saveRule(Long id, Long orgId, String name, String strategy,
                                        Map<String, Object> config, Boolean enabled, Boolean isDefault) {
        Long scope = orgId != null ? orgId : UserContext.scopeOrgId();
        Strategy st = Strategy.parse(strategy);
        Config parsed = config == null || config.isEmpty()
                ? Config.preset(st)
                : parseMap(config, Config.preset(st));
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
        rule.setStrategy(st.name());
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
            throw new ForbiddenException("平台默认规则仅平台管理员可删除");
        }
        recommendRuleMapper.deleteById(id);
    }

    /** 首次启动补一条平台默认规则，让规则在界面上可见、可调参 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapDefaultRules() {
        try {
            Long count = recommendRuleMapper.selectCount(new LambdaQueryWrapper<>());
            if (count != null && count > 0) {
                return;
            }
            RecommendRule rule = new RecommendRule();
            rule.setOrgId(null);
            rule.setName(DEFAULT_RULE_NAME);
            rule.setStrategy(Strategy.BALANCED.name());
            rule.setIsDefault(1);
            rule.setEnabled(1);
            rule.setConfig(MAPPER.writeValueAsString(Config.defaults().toMap()));
            recommendRuleMapper.insert(rule);
            log.warn("已创建默认推荐规则（平台级）: {}", DEFAULT_RULE_NAME);
        } catch (Exception e) {
            log.warn("初始化默认推荐规则失败: {}", e.getMessage());
        }
    }

    // ==================== 出题引擎公式（保持不变） ====================

    /**
     * 出题优先级：缺掌握度 + 知识点重要性。数值越大越该先抽题。
     *
     * <p>公式与出题引擎原有实现完全一致，保证出题行为不变。新推荐引擎见 {@link #score}。</p>
     */
    public double priorityScore(double mastery, double weight, double maxWeight, Config cfg) {
        double normalized = maxWeight == 0 ? 1 : weight / maxWeight;
        return (1 - mastery) * cfg.masteryWeight() + normalized * cfg.importanceWeight();
    }

    // ==================== 推荐引擎 ====================

    /**
     * 给学生算推荐学习顺序。
     *
     * <p>三段式：<b>打分 → 策略 → 配额裁剪</b>。</p>
     * <ol>
     *   <li>打分：六因子加权，得到「这个知识点该被多想要」；</li>
     *   <li>策略：在分数之上叠加排序偏好（补弱优先 / 顺序推进 / 应试优先），不改权重；</li>
     *   <li>裁剪：按每日条数、薄弱保底、复习配额、同父章节去重收敛成最终清单。
     *       这一步是必需的 —— 只按分数排序会出现「前 5 名全在同一个章节」这种
     *       分数上说得通、体验上很糟的结果。</li>
     * </ol>
     *
     * @param limit 最多返回多少条，null 用规则里的 dailyTaskLimit
     */
    public List<Recommendation> recommend(Long studentId, Integer limit) {
        return recommendWith(studentId, limit, null, null);
    }

    /**
     * 用指定配置与策略算推荐（不落库）。
     *
     * <p>配置页的「沙盒模拟」靠它：传一份草稿配置进来，就能看到排序会怎么变，
     * 而不用真的改线上配置。</p>
     */
    public List<Recommendation> recommendWith(Long studentId, Integer limit, Config override, Strategy overrideStrategy) {
        StudentGraph sg = latestStudentGraph(studentId);
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱，无法推荐学习内容");
        }
        Long graphId = sg.getSourceGraphId();
        Long orgId = null;
        var graph = graphStore.findGraph(graphId);
        if (graph != null) {
            orgId = graph.getOrgId();
        }
        Config cfg = override != null ? override : configOf(orgId);
        Strategy strategy = overrideStrategy != null ? overrideStrategy : strategyOf(orgId);

        List<GraphNode> nodes = graphStore.listNodes(graphId);
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<Long, MasteryProfileService.Profile> profiles = masteryProfileService.profiles(studentId, graphId, cfg);
        Map<Long, LocalDateTime> recent = cfg.repetitionPenalty() > 0
                ? learningRecordService.recentNodeActivity(studentId, cfg.recentRepeatWindowDays())
                : Map.of();

        Graph topology = topology(graphId);
        double maxWeight = nodes.stream()
                .map(n -> n.getWeight() == null ? 1.0 : n.getWeight().doubleValue())
                .max(Double::compare).orElse(1.0);

        // ---------- 阶段一：打分 ----------
        List<Recommendation> scored = new ArrayList<>();
        for (GraphNode node : nodes) {
            MasteryProfileService.Profile p = profiles.get(node.getId());
            scored.add(score(node, p, profiles, topology, maxWeight, recent, cfg));
        }

        // ---------- 阶段二：策略排序 ----------
        Comparator<Recommendation> cmp = comparatorOf(strategy);
        scored.sort(cmp);

        // ---------- 阶段三：配额与多样性裁剪 ----------
        int max = limit != null && limit > 0 ? limit : cfg.dailyTaskLimit();
        return quota(scored, cfg, max);
    }

    /** 图谱拓扑：前置关系 + 父节点，用于前置阻塞因子与同父去重 */
    private record Graph(Map<Long, List<Long>> prereqOf, Map<Long, List<Long>> dependentsOf,
                         Map<Long, Long> parentOf) {
    }

    private Graph topology(Long graphId) {
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        Map<Long, List<Long>> dependents = new HashMap<>();
        for (NodeRelation r : graphStore.listRelationsByType(graphId, RelationType.PREREQUISITE)) {
            // 语义：r.from 是 r.to 的前置
            prereqOf.computeIfAbsent(r.getToNodeId(), k -> new ArrayList<>()).add(r.getFromNodeId());
            dependents.computeIfAbsent(r.getFromNodeId(), k -> new ArrayList<>()).add(r.getToNodeId());
        }
        Map<Long, Long> parentOf = new HashMap<>();
        for (NodeRelation r : graphStore.listRelationsByType(graphId, RelationType.CONTAINS)) {
            parentOf.put(r.getToNodeId(), r.getFromNodeId());
        }
        return new Graph(prereqOf, dependents, parentOf);
    }

    /**
     * 六因子打分。
     *
     * <p>每个因子都归一化到 [0,1]，乘权重后求和。分项全部保留在
     * {@link Recommendation#factors} 里 —— 推荐结果不能只给一个分数，
     * 否则「为什么这个排第一」永远答不上来。</p>
     */
    private Recommendation score(GraphNode node, MasteryProfileService.Profile p,
                                 Map<Long, MasteryProfileService.Profile> profiles,
                                 Graph topology, double maxWeight,
                                 Map<Long, LocalDateTime> recent, Config cfg) {
        double effective = p == null ? 0 : p.decayed();
        int count = p == null ? 0 : p.practiceCount();
        int daysSince = p == null ? -1 : p.daysSince();
        String level = p == null ? MasteryProfileService.Level.UNSEEN.name() : p.level().name();
        String levelLabel = p == null ? MasteryProfileService.Level.UNSEEN.label : p.level().label;
        double confidence = p == null ? 0 : p.confidence();

        List<Factor> factors = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // 因子 1：掌握缺口。用衰减后的值 —— 上周考 90% 不代表现在还记着
        double gap = 1 - effective;
        factors.add(new Factor("gap", "掌握缺口", gap, cfg.masteryWeight(), gap * cfg.masteryWeight(), null));
        if (count == 0) {
            reasons.add("还没练过，从零开始");
        } else if (gap > 1 - cfg.solidThreshold()) {
            reasons.add("当前掌握度 " + pct(effective) + "，缺口大");
        }

        // 因子 2：遗忘风险。半衰期越短越容易触发
        double forgetting = daysSince < 0 ? 0
                : Math.min(1, Math.max(0, (daysSince - cfg.forgettingHalfLifeDays() / 2.0)
                / Math.max(1.0, cfg.forgettingHalfLifeDays())));
        factors.add(new Factor("forgetting", "遗忘风险", forgetting, cfg.forgettingWeight(),
                forgetting * cfg.forgettingWeight(), null));
        if (daysSince > cfg.forgettingHalfLifeDays()) {
            reasons.add("距上次练习 " + daysSince + " 天，超过遗忘半衰期");
        }

        // 因子 3：前置阻塞。它挡住的后继知识点越多越该先学
        List<Long> dependents = topology.dependentsOf().getOrDefault(node.getId(), List.of());
        int blocked = 0;
        List<String> blockedNames = new ArrayList<>();
        for (Long dep : dependents) {
            MasteryProfileService.Profile dp = profiles.get(dep);
            double depEffective = dp == null ? 0 : dp.decayed();
            if (depEffective < cfg.weakThreshold()) {
                blocked++;
                GraphNode dn = graphStore.findNode(dep);
                blockedNames.add(dn == null ? String.valueOf(dep) : dn.getName());
            }
        }
        double prereqScore = dependents.isEmpty() ? 0
                : Math.min(1, blocked / (double) Math.min(dependents.size(), 4));
        factors.add(new Factor("prereq", "前置阻塞", prereqScore, cfg.prereqBoost(),
                prereqScore * cfg.prereqBoost(), null));
        if (blocked > 0) {
            reasons.add("是 " + blocked + " 个薄弱知识点的前置：" + String.join("、",
                    blockedNames.subList(0, Math.min(2, blockedNames.size()))));
        }

        // 因子 4：知识点重要性（归一化权重）
        double weight = node.getWeight() == null ? 1.0 : node.getWeight().doubleValue();
        double normalized = maxWeight == 0 ? 1 : weight / maxWeight;
        factors.add(new Factor("importance", "知识点重要性", normalized, cfg.importanceWeight(),
                normalized * cfg.importanceWeight(), null));
        if (weight >= 1.5) {
            reasons.add("本册重点（重要性权重 " + trim(weight) + "）");
        }

        // 因子 5：低置信度。分高但只练过一次的，不该被当成真掌握
        double uncertainty = 1 - confidence;
        factors.add(new Factor("uncertainty", "低置信度", uncertainty, cfg.uncertaintyWeight(),
                uncertainty * cfg.uncertaintyWeight(), null));
        if (count > 0 && count < cfg.confidenceFullPractice()) {
            reasons.add("只练过 " + count + " 次，这个掌握度还不够可信");
        }

        double rawScore = factors.stream().mapToDouble(Factor::contribution).sum();

        // 扣分项：近期重复。学完就该让它沉下去，而不是明天再推一遍
        LocalDateTime lastActive = recent.get(node.getId());
        int repeatDays = lastActive == null ? -1 : MasteryProfileService.daysSince(lastActive);
        double repeatPenalty = 0;
        if (repeatDays >= 0 && cfg.repetitionPenalty() > 0) {
            repeatPenalty = cfg.repetitionPenalty();
            factors.add(new Factor("repeat", "近期重复", 1, -cfg.repetitionPenalty(),
                    -repeatPenalty, "最近 " + repeatDays + " 天内学过"));
            reasons.add("最近 " + repeatDays + " 天内刚学过，先放一放");
        }

        String tier = tierOf(effective, cfg);
        String action = suggestAction(tier, count, confidence, repeatDays >= 0);
        double finalScore = rawScore - repeatPenalty;

        return new Recommendation(node.getId(), node.getName(), node.getDescription(),
                topology.parentOf().get(node.getId()),
                BigDecimal.valueOf(effective).setScale(4, java.math.RoundingMode.HALF_UP),
                effective, count, confidence, daysSince, level, levelLabel,
                node.getWeight(), tier, finalScore, rawScore, factors, reasons, action);
    }

    private String tierOf(double effective, Config cfg) {
        if (effective < cfg.weakThreshold()) {
            return "WEAK";
        }
        if (effective >= cfg.solidThreshold()) {
            return "SOLID";
        }
        return "MID";
    }

    /** 建议动作：告诉前端该生成「学」「练」还是「复习」任务 */
    private String suggestAction(String tier, int count, double confidence, boolean recentlyPracticed) {
        if (tier.equals("WEAK")) {
            return count == 0 ? "LEARN" : "PRACTICE";
        }
        if (tier.equals("SOLID")) {
            return "REVIEW";
        }
        // 提升区间：置信度不足就先验证，分数够高就继续学
        return confidence < 0.5 ? "PRACTICE" : "LEARN";
    }

    // ==================== 策略排序 ====================

    /**
     * 策略排序器。
     *
     * <p>顺序<b>不可变</b>（靠 tierRank 分档保证薄弱永远在已掌握前面），
     * 策略只在<b>档内</b>调整次序。这样做是因为「已掌握优先于薄弱」在任何策略下都是错的，
     * 不该被参数化。</p>
     */
    private Comparator<Recommendation> comparatorOf(Strategy strategy) {
        Map<String, Integer> tierRank = Map.of("WEAK", 0, "MID", 1, "SOLID", 2, "UNSEEN", 0);
        Comparator<Recommendation> base = Comparator
                .comparingInt((Recommendation r) -> tierRank.getOrDefault(r.tier(), 3))
                .thenComparing(Comparator.comparingDouble(Recommendation::score).reversed());
        return switch (strategy) {
            case BALANCED -> base;
            // 补弱优先：档内按掌握度从低到高，缺口最大的先来
            case REMEDIATION -> Comparator
                    .comparingInt((Recommendation r) -> tierRank.getOrDefault(r.tier(), 3))
                    .thenComparingDouble(r -> r.effectiveMastery())
                    .thenComparing(Comparator.comparingDouble(Recommendation::score).reversed());
            // 应试优先：档内按重要性优先，重点先来
            case EXAM -> Comparator
                    .comparingInt((Recommendation r) -> tierRank.getOrDefault(r.tier(), 3))
                    .thenComparing(Comparator.comparingDouble(
                            (Recommendation r) -> factorValue(r, "importance")).reversed())
                    .thenComparing(Comparator.comparingDouble(Recommendation::score).reversed());
            // 顺序推进：档内按前置阻塞优先 —— 前置没学就别急着学后继
            case SEQUENTIAL -> Comparator
                    .comparingInt((Recommendation r) -> tierRank.getOrDefault(r.tier(), 3))
                    .thenComparing(Comparator.comparingDouble(
                            (Recommendation r) -> factorValue(r, "prereq")).reversed())
                    .thenComparing(Comparator.comparingDouble(Recommendation::score).reversed());
        };
    }

    private static double factorValue(Recommendation r, String key) {
        for (Factor f : r.factors()) {
            if (f.key().equals(key)) {
                return f.contribution();
            }
        }
        return 0;
    }

    // ==================== 配额与多样性裁剪 ====================

    /**
     * 收敛成最终清单。
     *
     * <p>三道约束，缺一不可：</p>
     * <ol>
     *   <li><b>薄弱保底</b>：先按分数取，再用保底名额把薄弱项提上来，
     *       避免一个孩子复习任务占满整天；</li>
     *   <li><b>复习配额</b>：反过来也成立 —— 全是薄弱会让孩子彻底不复习已学会的，
     *       所以限制复习项的占比；</li>
     *   <li><b>同父去重</b>：同一章节最多推 {@code maxPerParent} 个，
     *       否则会出现「今天推荐分数的 5 个兄弟节点」这种明显偷懒的结果。</li>
     * </ol>
     *
     * <p>约束冲突时的优先级：先满足保底，再满足去重，最后受复习配额压。
     * 宁可稍微超出配额，也不要让薄弱项被复习挤掉。</p>
     */
    private List<Recommendation> quota(List<Recommendation> sorted, Config cfg, int max) {
        if (sorted.isEmpty() || max <= 0) {
            return List.of();
        }
        int weakFloor = (int) Math.round(max * cfg.weakQuotaRatio());
        int reviewCap = (int) Math.round(max * cfg.reviewQuotaRatio());
        int maxPerParent = Math.max(1, cfg.maxPerParent());

        List<Recommendation> out = new ArrayList<>();
        Map<Long, Integer> perParent = new HashMap<>();
        List<Recommendation> deferred = new ArrayList<>();

        // 第一轮：按分数取，同时满足薄弱保底与同父去重
        int weakTaken = 0;
        int reviewTaken = 0;
        for (Recommendation r : sorted) {
            if (out.size() >= max) {
                break;
            }
            boolean isWeak = "WEAK".equals(r.tier());
            boolean isReview = "SOLID".equals(r.tier());
            if (isReview && reviewTaken >= reviewCap && weakTaken >= weakFloor) {
                // 复习名额已满且薄弱保底已达成：这个先跳过，后面还有薄弱项要留位置
                deferred.add(r);
                continue;
            }
            Long parent = parentKeyOf(r);
            int used = parent == null ? 0 : perParent.getOrDefault(parent, 0);
            if (parent != null && used >= maxPerParent) {
                deferred.add(r);
                continue;
            }
            out.add(r);
            if (parent != null) {
                perParent.merge(parent, 1, Integer::sum);
            }
            if (isWeak) {
                weakTaken++;
            }
            if (isReview) {
                reviewTaken++;
            }
        }

        // 第二轮：薄弱保底没达成时，从延后项里把最弱的提上来（可突破同父去重）
        if (weakTaken < weakFloor) {
            List<Recommendation> weakLeft = deferred.stream()
                    .filter(r -> "WEAK".equals(r.tier()))
                    .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                    .toList();
            for (Recommendation r : weakLeft) {
                if (out.size() >= max || weakTaken >= weakFloor) {
                    break;
                }
                if (out.contains(r)) {
                    continue;
                }
                out.add(r);
                weakTaken++;
            }
        }

        // 第三轮：位置还有空就按分数补齐（此时不再限复习配额）
        for (Recommendation r : deferred) {
            if (out.size() >= max) {
                break;
            }
            if (!out.contains(r)) {
                out.add(r);
            }
        }

        return out.stream()
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .toList();
    }

    /** 同父去重的键：优先用所属父节点；没有父节点则返回 null（等价于不限流） */
    private Long parentKeyOf(Recommendation r) {
        return r.parentNodeId();
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
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            return parseMap(raw, Config.defaults());
        } catch (Exception e) {
            log.warn("推荐规则解析失败，回退默认值: {}", e.getMessage());
            return Config.defaults();
        }
    }

    /** 缺失字段回落 preset，再回落内置默认 —— 老规则 JSON 只有 7 个字段时要能正常读出来 */
    private Config parseMap(Map<String, Object> map, Config preset) {
        if (map == null || map.isEmpty()) {
            return preset;
        }
        return new Config(
                num(map, "masteryWeight", preset.masteryWeight()),
                num(map, "importanceWeight", preset.importanceWeight()),
                num(map, "weakThreshold", preset.weakThreshold()),
                num(map, "solidThreshold", preset.solidThreshold()),
                num(map, "prereqBoost", preset.prereqBoost()),
                num(map, "consolidateRatio", preset.consolidateRatio()),
                (int) Math.round(num(map, "dailyTaskLimit", preset.dailyTaskLimit())),
                num(map, "forgettingWeight", preset.forgettingWeight()),
                num(map, "uncertaintyWeight", preset.uncertaintyWeight()),
                num(map, "repetitionPenalty", preset.repetitionPenalty()),
                num(map, "forgettingHalfLifeDays", preset.forgettingHalfLifeDays()),
                num(map, "decayFloorRatio", preset.decayFloorRatio()),
                (int) Math.round(num(map, "confidenceFullPractice", preset.confidenceFullPractice())),
                num(map, "weakQuotaRatio", preset.weakQuotaRatio()),
                num(map, "reviewQuotaRatio", preset.reviewQuotaRatio()),
                (int) Math.round(num(map, "recentRepeatWindowDays", preset.recentRepeatWindowDays())),
                (int) Math.round(num(map, "maxPerParent", preset.maxPerParent())));
    }

    /** 把草稿配置裁到合法区间，防止前端传进来 5 层的权重把打分算爆 */
    public Config sanitize(Map<String, Object> config, Strategy strategy) {
        return parseMap(config, Config.preset(strategy));
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
        m.put("strategy", rule.getStrategy());
        m.put("strategyLabel", Strategy.parse(rule.getStrategy()).label);
        m.put("isDefault", rule.getIsDefault());
        m.put("enabled", rule.getEnabled());
        m.put("config", rule.getConfig());
        m.put("configMap", parse(rule.getConfig()).toMap());
        m.put("createdAt", rule.getCreatedAt());
        m.put("updatedAt", rule.getUpdatedAt());
        return m;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
