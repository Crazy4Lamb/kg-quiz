package com.kgquiz.quiz.service;

import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 掌握程度评估：把「一个 0-1 的小数」翻译成「这个孩子到底会不会、这个判断有多可信、
 * 以及他多久没碰了」。推荐引擎与学情报告共用这里的口径，避免两处各算一套。
 *
 * <p>三个维度缺一不可：</p>
 * <ul>
 *   <li><b>水平</b>：{@link Level} 五级（未接触 / 薄弱 / 入门 / 熟练 / 掌握），由衰减后的掌握度决定。</li>
 *   <li><b>置信度</b>：{@link Confidence} 三档。练 1 次得 0.9 和练 12 次均分 0.9 不是一回事，
 *       低置信度的「掌握」会被刻意降权，优先安排一次验证。</li>
 *   <li><b>新鲜度</b>：艾宾浩斯式遗忘衰减。{@code decay = 2^(-距上次练习天数 / 半衰期)}，
 *       衰减后的值不直接改写 {@code mastery}（那是测评事实），只在计算时使用。
 *       下界锚在 {@code masteryPeak × decayFloor}，避免一个多月不练就永久被判为薄弱。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasteryProfileService {

    /** 掌握程度分级，从低到高 */
    public enum Level {
        UNSEEN("未接触", "grey"),
        WEAK("薄弱", "red"),
        BASIC("入门", "orange"),
        PROFICIENT("熟练", "blue"),
        MASTERED("掌握", "green");

        public final String label;
        public final String color;

        Level(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    /** 置信度：掌握度这个判断的可信程度 */
    public enum Confidence {
        LOW("偏低", "orange"),
        MEDIUM("一般", "gold"),
        HIGH("较高", "green");

        public final String label;
        public final String color;

        Confidence(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    /** 分级参数：与推荐规则里的阈值同源，避免「推荐说薄弱、报告说入门」 */
    public record Thresholds(double weak, double basic, double proficient) {

        public static Thresholds of(RecommendService.Config cfg) {
            double weak = cfg.weakThreshold();
            double solid = cfg.solidThreshold();
            // 中间一级取几何中点，保证「入门」在薄弱与熟练之间有合理宽度
            double basic = Math.sqrt(Math.max(1e-6, weak * solid));
            double proficient = solid + (1 - solid) * 0.6;
            return new Thresholds(weak, basic, proficient);
        }
    }

    /**
     * 单个知识点的掌握画像。
     *
     * @param raw        原始掌握度（最近一次测评的事实值，不含衰减）
     * @param decayed    遗忘衰减后的有效掌握度（推荐与分级都看这个）
     * @param delta      衰减掉了多少（= raw - decayed），用来提示「该复习了」
     * @param daysSince  距上次练习天数，没练过为 -1
     * @param confidenceLevel 置信度档
     */
    public record Profile(
            Long nodeId,
            double raw,
            double decayed,
            double delta,
            int practiceCount,
            int daysSince,
            Level level,
            Confidence confidenceLevel,
            double confidence,
            double confidenceFull,
            LocalDateTime lastPracticedAt,
            String trend) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", nodeId);
            m.put("mastery", round(raw));
            m.put("effectiveMastery", round(decayed));
            m.put("forgotDelta", round(delta));
            m.put("practiceCount", practiceCount);
            m.put("daysSincePractice", daysSince);
            m.put("level", level.name());
            m.put("levelLabel", level.label);
            m.put("levelColor", level.color);
            m.put("confidence", level == Level.UNSEEN ? 0 : round(confidence));
            m.put("confidenceLevel", level == Level.UNSEEN ? "LOW" : confidenceLevel.name());
            m.put("confidenceLabel", level == Level.UNSEEN ? "偏低" : confidenceLevel.label);
            m.put("confidenceColor", level == Level.UNSEEN ? "orange" : confidenceLevel.color);
            m.put("confidenceFull", confidenceFull);
            m.put("lastPracticedAt", lastPracticedAt);
            m.put("trend", trend);
            return m;
        }
    }

    private final MasteryService masteryService;
    private final StudentGraphMapper studentGraphMapper;
    private final GraphStore graphStore;

    /** 学生所复制图谱的模板 id；没复制过图谱返回 null */
    public Long graphIdOf(Long studentId) {
        StudentGraph sg = studentGraphMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        return sg == null ? null : sg.getSourceGraphId();
    }

    /** 学生所在图谱所属机构（图谱按机构隔离，配置也按机构取） */
    public Long orgIdOf(Long studentId) {
        Long graphId = graphIdOf(studentId);
        if (graphId == null) {
            return null;
        }
        var graph = graphStore.findGraph(graphId);
        return graph == null ? null : graph.getOrgId();
    }

    /**
     * 计算单个知识点的掌握画像。
     *
     * @param cfg 推荐规则配置（提供半衰期、置信度达标次数与分级阈值）
     */
    public Profile profile(Long studentId, Long nodeId, RecommendService.Config cfg) {
        StudentNodeState st = masteryService.stateOf(studentId, nodeId);
        if (st == null) {
            return profileOf(nodeId, null, cfg);
        }
        return profileOf(nodeId, st, cfg);
    }

    /** 批量计算，图谱内的所有节点都要给一份（没状态的按未接触） */
    public Map<Long, Profile> profiles(Long studentId, Long graphId, RecommendService.Config cfg) {
        if (graphId == null) {
            // 学生还没复制图谱：没有可评估的对象，返回空而不是抛错，让页面能正常渲染空态
            return Map.of();
        }
        Map<Long, StudentNodeState> states = masteryService.statesOf(studentId);
        List<GraphNode> nodes = graphStore.listNodes(graphId);
        Map<Long, Profile> out = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            out.put(n.getId(), profileOf(n.getId(), states.get(n.getId()), cfg));
        }
        return out;
    }

    private Profile profileOf(Long nodeId, StudentNodeState st, RecommendService.Config cfg) {
        double raw = st == null || st.getMastery() == null ? 0 : st.getMastery().doubleValue();
        int count = st == null || st.getPracticeCount() == null ? 0 : st.getPracticeCount();
        LocalDateTime last = st == null ? null : st.getLastPracticedAt();
        double peak = st == null || st.getMasteryPeak() == null ? 0 : st.getMasteryPeak().doubleValue();

        int daysSince = daysSince(last);
        double decayed = decay(raw, peak, daysSince, cfg);
        double confidence = confidence(count, cfg);
        Thresholds th = Thresholds.of(cfg);
        Level level = level(decayed, count, th);
        Confidence confLevel = confidenceLevel(confidence, count);
        String trend = trend(st, count);

        return new Profile(nodeId, raw, decayed, raw - decayed, count, daysSince,
                level, confLevel, confidence, cfg.confidenceFullPractice(), last, trend);
    }

    // ==================== 分级 ====================

    /**
     * 分级用<b>衰减后</b>的掌握度，而不是原始值。
     *
     * <p>原因：一个月前考 0.9 的孩子现在真实水平大概率不到 0.9，用原始值分级会让他
     * 一直停在「掌握」而不安排复习，等真到期考试才发现漏洞。</p>
     */
    private Level level(double decayed, int count, Thresholds th) {
        if (count == 0) {
            return Level.UNSEEN;
        }
        if (decayed < th.weak()) {
            return Level.WEAK;
        }
        if (decayed < th.basic()) {
            return Level.BASIC;
        }
        if (decayed < th.proficient()) {
            return Level.PROFICIENT;
        }
        return Level.MASTERED;
    }

    /**
     * 遗忘衰减：{@code decayed = max(raw × 2^(-d/H), peak × floor)}。
     *
     * <p>下界 {@code peak × decayFloor} 是有意为之 —— 纯粹的指数衰减会让任何长期不练的
     * 知识点最终归零，进而被永久推荐为「最该学」。加上锚点后，它会稳定停在
     * 「需要复习」这一档，而不是「最紧急」。</p>
     */
    private double decay(double raw, double peak, int daysSince, RecommendService.Config cfg) {
        if (daysSince < 0) {
            return 0;
        }
        double halfLife = Math.max(0.5, cfg.forgettingHalfLifeDays());
        double factor = Math.pow(2, -(double) daysSince / halfLife);
        double floor = peak * cfg.decayFloorRatio();
        return clamp(Math.max(raw * factor, floor));
    }

    /**
     * 置信度：{@code count / (count + K)}，K 取规则里的「置信度达标次数」。
     *
     * <p>用调和式而不是线性 {@code count/K}：线性在 {@code count=0} 时置信度为 0、
     * {@code count=K} 时为 1，中间是直线；调和式前期涨得快（1 次就有感觉），
     * 后期趋近 1 但永远不到，符合「练得越多越可信但没有绝对可信」的现实。</p>
     */
    private double confidence(int count, RecommendService.Config cfg) {
        if (count <= 0) {
            return 0;
        }
        double k = Math.max(0.5, cfg.confidenceFullPractice());
        return count / (count + k);
    }

    private Confidence confidenceLevel(double confidence, int count) {
        if (count <= 0) {
            return Confidence.LOW;
        }
        if (confidence >= 0.7) {
            return Confidence.HIGH;
        }
        if (confidence >= 0.4) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /**
     * 趋势：只看最近一次观测相对历史峰值的位置。
     *
     * <p>没有历史可比（首次练习）判 {@code FIRST}，等于峰值判 {@code HOLD}，
     * 明显掉下来判 {@code DOWN}。不做「与上一次比」是因为单次得分率噪声大，
     * 一次失误就报退步会让老师和学生都不相信这个指标。</p>
     */
    private String trend(StudentNodeState st, int count) {
        if (st == null || count <= 1) {
            return "FIRST";
        }
        double last = st.getLastScoreRate() == null ? 0 : st.getLastScoreRate().doubleValue();
        double peak = st.getMasteryPeak() == null ? 0 : st.getMasteryPeak().doubleValue();
        if (peak <= 0) {
            return "HOLD";
        }
        double gap = (peak - last) / peak;
        if (gap >= 0.2) {
            return "DOWN";
        }
        if (gap <= 0.05) {
            return "UP";
        }
        return "HOLD";
    }

    // ==================== 聚合视图 ====================

    /**
     * 某学生的掌握程度总览：分级分布 + 薄弱清单 + 整体画像。
     *
     * <p>阈值由调用方从推荐规则传入，所以「报告里说薄弱」和「推荐里排在前面」
     * 永远是同一个判定，不会出现两套口径互相打脸。本服务<b>不依赖</b>
     * {@link RecommendService}，是为了不让两个 Service 循环依赖。</p>
     */
    public Map<String, Object> overview(Long studentId, Long graphId, RecommendService.Config cfg) {
        Map<Long, Profile> profiles = profiles(studentId, graphId, cfg);

        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (Level l : Level.values()) {
            byLevel.put(l.name(), 0);
        }
        List<Map<String, Object>> weak = new ArrayList<>();
        List<Map<String, Object>> mastered = new ArrayList<>();
        List<Map<String, Object>> lowConfidence = new ArrayList<>();
        double sum = 0;
        double decayedSum = 0;
        int touched = 0;

        for (Profile p : profiles.values()) {
            byLevel.merge(p.level().name(), 1, Integer::sum);
            sum += p.raw();
            decayedSum += p.decayed();
            if (p.level() != Level.UNSEEN) {
                touched++;
            }
            Map<String, Object> item = p.toMap();
            item.put("nodeName", nodeName(p.nodeId()));
            if (p.level() == Level.WEAK) {
                weak.add(item);
            }
            if (p.level() == Level.MASTERED) {
                mastered.add(item);
            }
            if (p.level() != Level.UNSEEN && p.confidenceLevel() == Confidence.LOW) {
                lowConfidence.add(item);
            }
        }

        int total = profiles.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("graphId", graphId);
        out.put("totalNodes", total);
        out.put("touchedNodes", touched);
        out.put("coverage", total == 0 ? 0 : round((double) touched / total));
        out.put("avgMastery", total == 0 ? 0 : round(sum / total));
        out.put("avgEffectiveMastery", total == 0 ? 0 : round(decayedSum / total));
        out.put("byLevel", byLevel);
        out.put("levelDetail", levelDetail());
        out.put("weakNodes", weak);
        out.put("masteredNodes", mastered);
        out.put("lowConfidenceNodes", lowConfidence);
        out.put("config", cfg.toMap());
        return out;
    }

    /** 分级定义回给前端，让页面自己渲染图例与筛选器 */
    private List<Map<String, Object>> levelDetail() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Level l : Level.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", l.name());
            m.put("label", l.label);
            m.put("color", l.color);
            list.add(m);
        }
        return list;
    }

    private String nodeName(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        GraphNode n = graphStore.findNode(nodeId);
        return n == null ? null : n.getName();
    }

    // ==================== 工具 ====================

    /** 距上次练习天数；从未练习返回 -1（与「今天练过」=0 区分开） */
    public static int daysSince(LocalDateTime last) {
        if (last == null) {
            return -1;
        }
        return (int) Duration.between(last, LocalDateTime.now()).toDays();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
