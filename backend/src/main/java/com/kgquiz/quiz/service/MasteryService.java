package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.entity.LearningRecord;
import com.kgquiz.quiz.mapper.LearningRecordMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 掌握度计算。
 *
 * <p>三条更新路径，<b>互不等价，改动时务必分清</b>：</p>
 * <ol>
 *   <li>{@link #initializeMastery} 诊断后<b>覆盖式</b>写入起点 —— 不走 EMA。
 *       原因：诊断前掌握度恒为 0，走 EMA 会得到 {@code 0×(1-α)+α×rate}，
 *       真实水平被压缩到 α 倍，起点人为失真。诊断的意义正是「一次性确立起点」。</li>
 *   <li>{@link #updateMastery} 普通练习走 <b>EMA</b>：
 *       {@code mastery_new = old×(1-α) + α×得分率}。</li>
 *   <li>{@link #adjustByRateDelta} 教师改分后按<b>增量</b>修正
 *       {@code mastery += α×Δrate}。不重放历史答题记录，因为初始掌握度可能来自
 *       系统外（导入的历史学情），重放会把那些值抹掉。历史改分按后续练习次数衰减影响，
 *       后续诊断覆盖过的旧观测不再影响当前水平。</li>
 * </ol>
 *
 * <p>三条路径都会同步维护 {@code practiceCount}（置信度分母）、{@code lastScoreRate}
 * 与 {@code masteryPeak}，并返回前后变化供学习记录流水落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasteryService {

    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final KgProperties kgProperties;
    private final LearningRecordMapper learningRecordMapper;

    /**
     * 一次掌握度变化：{@code before} 为动作前的值，{@code after} 为动作后的值。
     * 学习记录流水靠它把「推荐 → 执行 → 掌握度变化」串成可回溯的轨迹。
     */
    public record MasteryChange(Long nodeId, double before, double after, Integer practiceCount) {
    }

    /**
     * 按节点批量更新掌握度（普通练习走 EMA）。
     *
     * @param studentId        学生 id
     * @param nodeIdToScoreRate 节点 -> 本次平均得分率 [0,1]
     * @return 节点 -> 掌握度前后变化
     */
    public Map<Long, MasteryChange> updateMastery(Long studentId, Map<Long, Double> nodeIdToScoreRate) {
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度更新", studentId);
            return Map.of();
        }
        double alpha = kgProperties.getExam().getLearningRate();
        return apply(sg, nodeIdToScoreRate, (old, rate) -> old * (1 - alpha) + alpha * rate);
    }

    /**
     * 诊断初始化（覆盖式）：把本次诊断的得分率<b>直接写成起点掌握度</b>，不走 EMA。
     *
     * @param nodeIdToScoreRate 节点 -> 诊断得分率 [0,1]；诊断未覆盖到的节点保持原值
     */
    public Map<Long, MasteryChange> initializeMastery(Long studentId, Map<Long, Double> nodeIdToScoreRate) {
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度初始化", studentId);
            return Map.of();
        }
        return apply(sg, nodeIdToScoreRate, (old, rate) -> rate);
    }

    /** 诊断未覆盖到的知识点：起点重置为 0（学生还没机会展示水平，保守从零开始） */
    public void resetMastery(Long studentId, java.util.Collection<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            return;
        }
        for (Long nodeId : nodeIds) {
            StudentNodeState state = stateByGraph(sg.getId(), nodeId);
            state.setMastery(BigDecimal.ZERO);
            state.setLastPracticedAt(null);
            state.setLastScoreRate(null);
            state.setPracticeCount(0);
            state.setMasteryPeak(BigDecimal.ZERO);
            persist(state);
        }
    }

    /**
     * 教师改分后按差值修正掌握度：{@code mastery = clamp(old + α×Δrate)}。
     *
     * @param nodeRateDelta 节点 -> 得分率变化量（可正可负）
     */
    public Map<Long, MasteryChange> adjustByRateDelta(Long studentId, Map<Long, Double> nodeRateDelta) {
        return adjustByRateDelta(studentId, nodeRateDelta, kgProperties.getExam().getLearningRate());
    }

    /** 改分是修正已有观测，不是新增练习；诊断覆盖式观测的系数为 1。 */
    public Map<Long, MasteryChange> adjustByRateDelta(Long studentId, Map<Long, Double> nodeRateDelta,
                                                    double coefficient) {
        return correct(studentId, nodeRateDelta, Map.of(), coefficient, Map.of());
    }

    /** 保留后续练习的 EMA 权重：旧观测的修正 = 原系数 × (1-α)^后续练习次数 × Δrate。 */
    public Map<Long, MasteryChange> correctExam(Long studentId, Long examId, boolean diagnosis,
                                               Map<Long, Double> delta, Map<Long, Double> correctedRates) {
        if (delta == null || delta.isEmpty()) return Map.of();
        var history = learningRecordMapper.selectList(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getStudentId, studentId)
                .in(LearningRecord::getNodeId, delta.keySet())
                .and(q -> q.eq(LearningRecord::getRefType, LearningRecord.R_EXAM).or().eq(LearningRecord::getRefType, "NODE"))
                .in(LearningRecord::getAction, LearningRecord.A_PRACTICE, LearningRecord.A_DIAGNOSIS, LearningRecord.A_ADJUST)
                .isNotNull(LearningRecord::getMasteryAfter)
                .orderByAsc(LearningRecord::getId));
        double alpha = kgProperties.getExam().getLearningRate();
        double base = diagnosis ? 1 : alpha;
        Map<Long, Double> coefficients = new LinkedHashMap<>();
        Map<Long, Double> latestRates = new LinkedHashMap<>();
        for (Long nodeId : delta.keySet()) {
            LearningRecord target = null;
            double coefficient = base;
            boolean latest = true;
            for (LearningRecord event : history) {
                if (!nodeId.equals(event.getNodeId())) continue;
                if (LearningRecord.A_ADJUST.equals(event.getAction()) && !"NODE".equals(event.getRefType())) continue;
                if (examId.equals(event.getRefId()) && !"NODE".equals(event.getRefType())) {
                    target = event;
                    coefficient = base;
                    latest = true;
                } else if (target != null) {
                    coefficient = LearningRecord.A_DIAGNOSIS.equals(event.getAction()) || "NODE".equals(event.getRefType())
                            ? 0 : coefficient * (1 - alpha);
                    latest = false;
                }
            }
            coefficients.put(nodeId, coefficient);
            if (target != null && latest && correctedRates.containsKey(nodeId)) latestRates.put(nodeId, correctedRates.get(nodeId));
        }
        return correct(studentId, delta, coefficients, base, latestRates);
    }

    private Map<Long, MasteryChange> correct(Long studentId, Map<Long, Double> nodeRateDelta,
                                             Map<Long, Double> coefficients, double fallback,
                                             Map<Long, Double> latestRates) {
        if (nodeRateDelta == null || nodeRateDelta.isEmpty()) {
            return Map.of();
        }
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度修正", studentId);
            return Map.of();
        }
        Map<Long, MasteryChange> changes = new LinkedHashMap<>();
        for (var entry : nodeRateDelta.entrySet()) {
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                continue;
            }
            double delta = Math.max(-1, Math.min(1, entry.getValue()));
            StudentNodeState state = stateByGraph(sg.getId(), entry.getKey());
            double before = state.getMastery() == null ? 0 : state.getMastery().doubleValue();
            double after = clamp(before + coefficients.getOrDefault(entry.getKey(), fallback) * delta);
            state.setMastery(scale(after));
            if (latestRates.containsKey(entry.getKey())) state.setLastScoreRate(scale(latestRates.get(entry.getKey())));
            // 保留练习次数与练习时间，避免改分伪造学习行为。
            persist(state);
            changes.put(entry.getKey(), new MasteryChange(entry.getKey(), before, after,
                    state.getPracticeCount()));
        }
        return changes;
    }

    /** 当前学生掌握状态（按模板节点 id 索引） */
    public Map<Long, StudentNodeState> statesOf(Long studentId) {
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            return Map.of();
        }
        Map<Long, StudentNodeState> out = new LinkedHashMap<>();
        for (StudentNodeState s : studentNodeStateMapper.selectList(
                new LambdaQueryWrapper<StudentNodeState>().eq(StudentNodeState::getStudentGraphId, sg.getId()))) {
            out.put(s.getSourceNodeId(), s);
        }
        return out;
    }

    /** 单个知识点的掌握状态，没有则返回 null */
    public StudentNodeState stateOf(Long studentId, Long nodeId) {
        StudentGraph sg = latestGraph(studentId);
        if (sg == null || nodeId == null) {
            return null;
        }
        return studentNodeStateMapper.selectOne(new LambdaQueryWrapper<StudentNodeState>()
                .eq(StudentNodeState::getStudentGraphId, sg.getId())
                .eq(StudentNodeState::getSourceNodeId, nodeId));
    }

    // ==================== 内部 ====================

    /** 掌握度更新策略：由调用方决定「旧值 + 本次观测」怎么合成新值 */
    private interface Merge {
        double apply(double old, double observed);
    }

    private Map<Long, MasteryChange> apply(StudentGraph sg, Map<Long, Double> observedByNode, Merge merge) {
        if (observedByNode == null || observedByNode.isEmpty()) {
            return Map.of();
        }
        Map<Long, MasteryChange> changes = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : observedByNode.entrySet()) {
            Long nodeId = entry.getKey();
            double observed = Math.max(0, Math.min(1, entry.getValue()));

            StudentNodeState state = stateByGraph(sg.getId(), nodeId);
            double before = state.getMastery() == null ? 0 : state.getMastery().doubleValue();
            double after = clamp(merge.apply(before, observed));

            state.setMastery(scale(after));
            state.setLastScoreRate(scale(observed));
            state.setLastPracticedAt(LocalDateTime.now());
            // 峰值只在有真实观测时抬升；衰减类操作不应该刷高历史峰值
            double peak = state.getMasteryPeak() == null ? 0 : state.getMasteryPeak().doubleValue();
            state.setMasteryPeak(scale(Math.max(peak, after)));
            state.setPracticeCount((state.getPracticeCount() == null ? 0 : state.getPracticeCount()) + 1);
            persist(state);

            changes.put(nodeId, new MasteryChange(nodeId, before, after, state.getPracticeCount()));
            log.debug("掌握度更新 student={} node={} {} -> {} (第 {} 次)",
                    sg.getStudentId(), nodeId, fmt(before), fmt(after), state.getPracticeCount());
        }
        return changes;
    }

    private StudentGraph latestGraph(Long studentId) {
        return studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
    }

    /** 按学生图谱副本 + 模板节点取状态，没有则新建一个空状态 */
    private StudentNodeState stateByGraph(Long studentGraphId, Long nodeId) {
        StudentNodeState state = studentNodeStateMapper.selectOne(new LambdaQueryWrapper<StudentNodeState>()
                .eq(StudentNodeState::getStudentGraphId, studentGraphId)
                .eq(StudentNodeState::getSourceNodeId, nodeId));
        if (state == null) {
            state = new StudentNodeState();
            state.setStudentGraphId(studentGraphId);
            state.setSourceNodeId(nodeId);
            state.setMastery(BigDecimal.ZERO);
            state.setPracticeCount(0);
            state.setMasteryPeak(BigDecimal.ZERO);
        }
        return state;
    }

    private void persist(StudentNodeState state) {
        if (state.getId() == null) {
            studentNodeStateMapper.insert(state);
        } else {
            studentNodeStateMapper.updateById(state);
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
