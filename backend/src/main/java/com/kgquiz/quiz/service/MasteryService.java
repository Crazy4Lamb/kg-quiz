package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 掌握度计算：指数移动平均
 * mastery_new = mastery_old * (1 - alpha) + alpha * 得分率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasteryService {

    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final KgProperties kgProperties;

    /**
     * 按节点批量更新掌握度
     *
     * @param studentId        学生 id
     * @param nodeIdToScoreRate 节点 -> 本次平均得分率 [0,1]
     */
    public void updateMastery(Long studentId, Map<Long, Double> nodeIdToScoreRate) {
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度更新", studentId);
            return;
        }
        double alpha = kgProperties.getExam().getLearningRate();

        for (Map.Entry<Long, Double> entry : nodeIdToScoreRate.entrySet()) {
            Long nodeId = entry.getKey();
            double scoreRate = Math.max(0, Math.min(1, entry.getValue()));

            StudentNodeState state = studentNodeStateMapper.selectOne(
                    new LambdaQueryWrapper<StudentNodeState>()
                            .eq(StudentNodeState::getStudentGraphId, sg.getId())
                            .eq(StudentNodeState::getSourceNodeId, nodeId));
            if (state == null) {
                // 模板图谱在学生复制后新增的节点，补建状态
                state = new StudentNodeState();
                state.setStudentGraphId(sg.getId());
                state.setSourceNodeId(nodeId);
                state.setMastery(BigDecimal.ZERO);
            }
            double old = state.getMastery() == null ? 0 : state.getMastery().doubleValue();
            double updated = old * (1 - alpha) + alpha * scoreRate;
            state.setMastery(BigDecimal.valueOf(updated).setScale(4, RoundingMode.HALF_UP));
            state.setLastPracticedAt(LocalDateTime.now());
            if (state.getId() == null) {
                studentNodeStateMapper.insert(state);
            } else {
                studentNodeStateMapper.updateById(state);
            }
            log.debug("掌握度更新 student={} node={} {} -> {}", studentId, nodeId,
                    String.format("%.3f", old), String.format("%.3f", updated));
        }
    }

    /**
     * 诊断初始化（覆盖式）：把本次诊断的得分率<b>直接写成起点掌握度</b>，不再走 EMA。
     *
     * <p>为什么不能走 EMA：诊断前掌握度恒为 0，用 EMA 更新会得到 {@code 0*(1-α)+α*rate}，
     * 即真实水平被压缩到 α 倍，起点被人为压低。诊断的意义正是「一次性确立起点」。</p>
     *
     * @param nodeIdToScoreRate 节点 -> 诊断得分率 [0,1]；诊断未覆盖到的节点保持原值
     */
    public void initializeMastery(Long studentId, Map<Long, Double> nodeIdToScoreRate) {
        StudentGraph sg = latestGraph(studentId);
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度初始化", studentId);
            return;
        }
        for (Map.Entry<Long, Double> entry : nodeIdToScoreRate.entrySet()) {
            double rate = Math.max(0, Math.min(1, entry.getValue()));
            StudentNodeState state = stateOf(sg.getId(), entry.getKey());
            double old = state.getMastery() == null ? 0 : state.getMastery().doubleValue();
            state.setMastery(BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP));
            state.setLastPracticedAt(LocalDateTime.now());
            persist(state);
            log.info("诊断初始化掌握度 student={} node={} {} -> {}", studentId, entry.getKey(),
                    String.format("%.3f", old), String.format("%.3f", rate));
        }
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
            StudentNodeState state = stateOf(sg.getId(), nodeId);
            state.setMastery(BigDecimal.ZERO);
            state.setLastPracticedAt(null);
            persist(state);
        }
    }

    private StudentGraph latestGraph(Long studentId) {
        return studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
    }

    private StudentNodeState stateOf(Long studentGraphId, Long nodeId) {
        StudentNodeState state = studentNodeStateMapper.selectOne(new LambdaQueryWrapper<StudentNodeState>()
                .eq(StudentNodeState::getStudentGraphId, studentGraphId)
                .eq(StudentNodeState::getSourceNodeId, nodeId));
        if (state == null) {
            state = new StudentNodeState();
            state.setStudentGraphId(studentGraphId);
            state.setSourceNodeId(nodeId);
            state.setMastery(BigDecimal.ZERO);
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

    /**
     * 教师改分后按差值修正掌握度：mastery += alpha * (新得分率 - 旧得分率)。
     *
     * <p>不做"重放全部答题记录"，因为初始掌握度可能来自系统外（例如导入的历史学情），
     * 重放会把那些值抹掉。EMA 是线性的，用增量修正等价于把这次的得分率替换后重算一次。</p>
     *
     * @param nodeRateDelta 节点 -> 得分率变化量（可正可负）
     */
    public void adjustByRateDelta(Long studentId, Map<Long, Double> nodeRateDelta) {
        if (nodeRateDelta == null || nodeRateDelta.isEmpty()) {
            return;
        }
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            log.warn("学生 {} 无图谱副本，跳过掌握度修正", studentId);
            return;
        }
        double alpha = kgProperties.getExam().getLearningRate();

        for (Map.Entry<Long, Double> entry : nodeRateDelta.entrySet()) {
            double delta = entry.getValue() == null ? 0 : entry.getValue();
            if (Math.abs(delta) < 1e-9) {
                continue;
            }
            Long nodeId = entry.getKey();
            StudentNodeState state = studentNodeStateMapper.selectOne(
                    new LambdaQueryWrapper<StudentNodeState>()
                            .eq(StudentNodeState::getStudentGraphId, sg.getId())
                            .eq(StudentNodeState::getSourceNodeId, nodeId));
            if (state == null) {
                state = new StudentNodeState();
                state.setStudentGraphId(sg.getId());
                state.setSourceNodeId(nodeId);
                state.setMastery(BigDecimal.ZERO);
            }
            double old = state.getMastery() == null ? 0 : state.getMastery().doubleValue();
            double updated = Math.max(0, Math.min(1, old + alpha * delta));
            state.setMastery(BigDecimal.valueOf(updated).setScale(4, RoundingMode.HALF_UP));
            state.setLastPracticedAt(LocalDateTime.now());
            if (state.getId() == null) {
                studentNodeStateMapper.insert(state);
            } else {
                studentNodeStateMapper.updateById(state);
            }
            log.debug("掌握度修正 student={} node={} {} -> {} (delta={})", studentId, nodeId,
                    String.format("%.3f", old), String.format("%.3f", updated),
                    String.format("%.3f", delta));
        }
    }
}
