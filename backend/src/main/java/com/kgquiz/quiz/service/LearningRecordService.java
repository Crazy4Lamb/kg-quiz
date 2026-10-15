package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.LearningRecord;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.mapper.LearningRecordMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点学习记录：写入与查询。
 *
 * <p>与 {@link com.kgquiz.quiz.entity.LearningTask} 的分工是刻意分开的：</p>
 * <ul>
 *   <li>任务表记「该做什么」，可以被「按最新掌握度重排」覆盖；</li>
 *   <li>记录表记「实际做了什么、掌握度因此怎么变了」，<b>只追加不改写</b>。</li>
 * </ul>
 *
 * <p>推荐引擎的三个因子完全依赖本表，所以它不能被任务重排冲掉，否则「刚练过就再推一遍」
 * 这种问题无法从数据上根治。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordService {

    private final LearningRecordMapper learningRecordMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final GraphStore graphStore;
    private final MasteryService masteryService;

    // ==================== 写入 ====================

    /**
     * 记一条学习行为。
     *
     * <p>方法本身不抛错：写记录失败不应阻断主流程（交卷、完成任务），
     * 否则「流水表有问题」会变成「学生交不了卷」。失败只记日志。</p>
     *
     * @param studentId 学生
     * @param nodeId    知识点，可空（入学诊断等无知识点级的事件）
     * @param action    见 {@link LearningRecord} 常量
     * @param refType   关联对象类型
     * @param refId     关联对象 id
     * @param taskId    触发的学习任务 id（用于回溯推荐→执行闭环）
     * @param scoreRate 得分率，可空
     * @param durationSec 时长（秒）
     * @param questionCount 题数
     * @param change    掌握度前后变化，可空
     * @param note      备注
     */
    public void record(Long studentId, Long nodeId, String action, String refType, Long refId,
                       Long taskId, Double scoreRate, Integer durationSec, Integer questionCount,
                       MasteryService.MasteryChange change, String note) {
        try {
            Long orgId = orgIdOf(studentId);
            LearningRecord r = new LearningRecord();
            r.setOrgId(orgId);
            r.setStudentId(studentId);
            r.setNodeId(nodeId);
            r.setAction(action);
            r.setRefType(refType == null ? "" : refType);
            r.setRefId(refId);
            r.setTaskId(taskId);
            r.setScoreRate(scoreRate == null ? null : scale(scoreRate));
            r.setDurationSec(durationSec == null ? 0 : Math.max(0, durationSec));
            r.setQuestionCount(questionCount == null ? 0 : Math.max(0, questionCount));
            r.setMasteryBefore(change == null ? null : scale(change.before()));
            r.setMasteryAfter(change == null ? null : scale(change.after()));
            r.setNote(note == null ? "" : truncate(note));
            learningRecordMapper.insert(r);
        } catch (Exception e) {
            log.warn("写学习记录失败 student={} node={} action={}: {}", studentId, nodeId, action, e.getMessage());
        }
    }

    /** 看讲解 */
    public void recordView(Long studentId, Long nodeId, Long contentId, Integer durationSec, Long taskId) {
        record(studentId, nodeId, LearningRecord.A_VIEW, LearningRecord.R_CONTENT, contentId,
                taskId, null, durationSec, 0, null, "阅读知识点讲解");
    }

    /**
     * 交卷：按知识点逐条写记录。
     *
     * <p>每个知识点一条而不是整卷一条 —— 掌握度是知识点级的，轨迹也必须知识点级，
     * 否则「这个孩子是在哪个知识点上开始掉队的」就答不出来了。</p>
     *
     * @param rateByNode   节点 -> 本次得分率
     * @param changes      {@link MasteryService#updateMastery} 等返回的掌握度变化
     * @param questionCountByNode 节点 -> 题数
     */
    public void recordPractice(Long studentId, String action, Long examId, String examName,
                               Map<Long, Double> rateByNode,
                               Map<Long, MasteryService.MasteryChange> changes,
                               Map<Long, Integer> questionCountByNode) {
        if (rateByNode == null || rateByNode.isEmpty()) {
            // 无知识点级得分（整卷零分或卷内无有效题）：仍记一条，保留「做过」这个事实
            record(studentId, null, action, LearningRecord.R_EXAM, examId, null,
                    null, null, null, null, examName);
            return;
        }
        for (Map.Entry<Long, Double> e : rateByNode.entrySet()) {
            MasteryService.MasteryChange change = changes == null ? null : changes.get(e.getKey());
            Integer qc = questionCountByNode == null ? null : questionCountByNode.get(e.getKey());
            record(studentId, e.getKey(), action, LearningRecord.R_EXAM, examId, null,
                    e.getValue(), null, qc, change,
                    examName + (change == null ? "" : "（掌握度 " + pct(change.before()) + " → " + pct(change.after()) + "）"));
        }
    }

    /** 教师改分 */
    public void recordAdjust(Long studentId, Long nodeId, Long examId, Double scoreRate,
                             MasteryService.MasteryChange change, String note) {
        record(studentId, nodeId, LearningRecord.A_ADJUST, LearningRecord.R_EXAM, examId, null,
                scoreRate, null, 0, change, note);
    }

    /** 完成任务（任务视角的一条汇总记录） */
    public void recordTaskDone(Long studentId, Long nodeId, String type, Long taskId, String title) {
        String action = LearningTask_typeToAction(type);
        record(studentId, nodeId, action, LearningRecord.R_TASK, taskId, taskId,
                null, null, 0, null, "完成任务：" + title);
    }

    private static String LearningTask_typeToAction(String taskType) {
        if (taskType == null) {
            return LearningRecord.A_TASK_DONE;
        }
        return switch (taskType) {
            case "PRACTICE" -> LearningRecord.A_PRACTICE;
            case "REVIEW" -> LearningRecord.A_REVIEW;
            case "DIAGNOSIS" -> LearningRecord.A_DIAGNOSIS;
            default -> LearningRecord.A_TASK_DONE;
        };
    }

    // ==================== 查询 ====================

    /** 学习时间线（按时间倒序），支持按动作类型与知识点过滤 */
    public List<Map<String, Object>> timeline(Long studentId, String action, Long nodeId, Integer limit) {
        UserContext.checkStudentScope(studentId);
        LambdaQueryWrapper<LearningRecord> w = new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getStudentId, studentId)
                .orderByDesc(LearningRecord::getCreatedAt)
                .orderByDesc(LearningRecord::getId)
                .last("limit " + Math.max(1, Math.min(500, limit == null ? 100 : limit)));
        if (action != null && !action.isBlank()) {
            w.eq(LearningRecord::getAction, action.trim().toUpperCase());
        }
        if (nodeId != null) {
            w.eq(LearningRecord::getNodeId, nodeId);
        }
        return learningRecordMapper.selectList(w).stream().map(this::toView).toList();
    }

    /**
     * 单个知识点的掌握轨迹：按时间正序的行为 + 掌握度变化。
     *
     * <p>教师端最常用的视角 —— 「这个知识点上他经历了什么」，
     * 比一个孤零零的掌握度数字有用得多。</p>
     */
    public Map<String, Object> nodeTimeline(Long studentId, Long nodeId) {
        UserContext.checkStudentScope(studentId);
        List<LearningRecord> records = learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                        .eq(LearningRecord::getStudentId, studentId)
                        .eq(LearningRecord::getNodeId, nodeId)
                        .orderByAsc(LearningRecord::getCreatedAt)
                        .orderByAsc(LearningRecord::getId));
        GraphNode node = nodeId == null ? null : graphStore.findNode(nodeId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", nodeId);
        out.put("nodeName", node == null ? null : node.getName());
        out.put("nodeDescription", node == null ? null : node.getDescription());
        out.put("records", records.stream().map(this::toView).toList());
        out.put("eventCount", records.size());
        return out;
    }

    /**
     * 学习统计聚合。
     *
     * <p>按「最近 N 天」统计而不是全量：全量数字会因为学期拉长而失去指导意义，
     * 「最近 7 天学了 5 个知识点、正确率 62%」才是学生明天能据此行动的信息。</p>
     */
    public Map<String, Object> stats(Long studentId, Integer days) {
        UserContext.checkStudentScope(studentId);
        int window = Math.max(1, Math.min(365, days == null ? 7 : days));
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        List<LearningRecord> all = learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                        .eq(LearningRecord::getStudentId, studentId)
                        .ge(LearningRecord::getCreatedAt, since)
                        .orderByAsc(LearningRecord::getCreatedAt));

        Map<String, Integer> byAction = new LinkedHashMap<>();
        Set<Long> nodeIds = new LinkedHashSet<>();
        Map<String, Integer> byDate = new LinkedHashMap<>();
        double rateSum = 0;
        int rateCount = 0;
        int duration = 0;
        int questions = 0;
        for (LocalDate d = LocalDate.now().minusDays(window - 1); !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            byDate.put(d.toString(), 0);
        }

        for (LearningRecord r : all) {
            byAction.merge(r.getAction(), 1, Integer::sum);
            if (r.getNodeId() != null) {
                nodeIds.add(r.getNodeId());
            }
            if (r.getScoreRate() != null) {
                rateSum += r.getScoreRate().doubleValue();
                rateCount++;
            }
            duration += nz(r.getDurationSec());
            questions += nz(r.getQuestionCount());
            String day = r.getCreatedAt() == null ? null : r.getCreatedAt().toLocalDate().toString();
            if (day != null) {
                byDate.merge(day, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byDate.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("count", e.getValue());
            trend.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("days", window);
        out.put("eventCount", all.size());
        out.put("nodeCount", nodeIds.size());
        out.put("totalDurationSec", duration);
        out.put("questionCount", questions);
        out.put("avgScoreRate", rateCount == 0 ? null : round(rateSum / rateCount));
        out.put("byAction", byAction);
        out.put("dailyTrend", trend);
        out.put("actionDetail", actionDetail());
        out.put("records", all.stream().map(this::toView).toList());
        return out;
    }

    /**
     * 最近 N 天内被学习过的知识点 -> 最近一次时间。
     *
     * <p>推荐引擎的「近期重复惩罚」因子直接吃这个结果，避免学生刚练完的知识点
     * 又被排在第一。</p>
     */
    public Map<Long, LocalDateTime> recentNodeActivity(Long studentId, int withinDays) {
        int window = Math.max(1, withinDays);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        List<LearningRecord> records = learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                        .eq(LearningRecord::getStudentId, studentId)
                        .ge(LearningRecord::getCreatedAt, since)
                        .eq(LearningRecord::getNodeId, 1));
        Map<Long, LocalDateTime> out = new LinkedHashMap<>();
        for (LearningRecord r : records) {
            if (r.getNodeId() == null || r.getCreatedAt() == null) {
                continue;
            }
            LocalDateTime exist = out.get(r.getNodeId());
            if (exist == null || r.getCreatedAt().isAfter(exist)) {
                out.put(r.getNodeId(), r.getCreatedAt());
            }
        }
        return out;
    }

    /** 动作类型说明回给前端，页面自己渲染图标与文案 */
    private List<Map<String, Object>> actionDetail() {
        return List.of(
                actionView(LearningRecord.A_VIEW, "看讲解", "blue"),
                actionView(LearningRecord.A_PRACTICE, "做题", "green"),
                actionView(LearningRecord.A_DIAGNOSIS, "诊断", "red"),
                actionView(LearningRecord.A_REVIEW, "复习", "gold"),
                actionView(LearningRecord.A_TASK_DONE, "完成任务", "purple"),
                actionView(LearningRecord.A_ADJUST, "教师改分", "orange"));
    }

    private Map<String, Object> actionView(String action, String label, String color) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("label", label);
        m.put("color", color);
        return m;
    }

    private Map<String, Object> toView(LearningRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("studentId", r.getStudentId());
        m.put("nodeId", r.getNodeId());
        m.put("nodeName", r.getNodeId() == null ? null : nodeName(r.getNodeId()));
        m.put("action", r.getAction());
        m.put("refType", r.getRefType());
        m.put("refId", r.getRefId());
        m.put("taskId", r.getTaskId());
        m.put("scoreRate", r.getScoreRate());
        m.put("durationSec", nz(r.getDurationSec()));
        m.put("questionCount", nz(r.getQuestionCount()));
        m.put("masteryBefore", r.getMasteryBefore());
        m.put("masteryAfter", r.getMasteryAfter());
        m.put("note", r.getNote());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private String nodeName(Long nodeId) {
        GraphNode n = graphStore.findNode(nodeId);
        return n == null ? null : n.getName();
    }

    // ==================== 工具 ====================

    /** 学生所在机构；用于给记录打租户标记。取不到不阻断写入（记录本身可查） */
    private Long orgIdOf(Long studentId) {
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg != null) {
            var graph = graphStore.findGraph(sg.getSourceGraphId());
            if (graph != null && graph.getOrgId() != null) {
                return graph.getOrgId();
            }
        }
        // 退而求其次：取该生已有记录里的机构，再退到学生实体
        LearningRecord any = learningRecordMapper.selectOne(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getStudentId, studentId)
                .orderByDesc(LearningRecord::getId)
                .last("limit 1"));
        if (any != null && any.getOrgId() != null) {
            return any.getOrgId();
        }
        throw new BizException("无法确定学生所属机构，未写入学习记录");
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(Math.max(0, Math.min(1, v))).setScale(4, RoundingMode.HALF_UP);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 252) + "...";
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }
}
