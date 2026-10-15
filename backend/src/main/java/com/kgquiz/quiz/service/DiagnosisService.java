package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.AnswerRecord;
import com.kgquiz.quiz.entity.Exam;
import com.kgquiz.quiz.entity.ExamAssignment;
import com.kgquiz.quiz.entity.ExamQuestion;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.AnswerRecordMapper;
import com.kgquiz.quiz.mapper.ExamAssignmentMapper;
import com.kgquiz.quiz.mapper.ExamMapper;
import com.kgquiz.quiz.mapper.ExamQuestionMapper;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入学诊断：一次覆盖全部知识点的摸底测验，交卷后<b>覆盖式</b>确立掌握度起点。
 *
 * <p>与普通练习的区别：</p>
 * <ul>
 *   <li>出题策略不同 —— 按「每个知识点抽 1 道最有区分度的题」铺满整张图谱，
 *       而不是按掌握度加权挑薄弱点（诊断时学生还没有掌握度，加权没有意义）；</li>
 *   <li>掌握度写入方式不同 —— 诊断得分率<b>直接写成</b>起点掌握度（见
 *       {@link MasteryService#initializeMastery}），普通练习走 EMA 增量。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 诊断卷每题默认分值 */
    private static final BigDecimal DEFAULT_SCORE = BigDecimal.valueOf(10);

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAssignmentMapper assignmentMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final StudentMapper studentMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final GraphStore graphStore;
    private final RecommendService recommendService;
    private final StudentAccessService studentAccessService;

    // ==================== 生成诊断卷 ====================

    /**
     * 给某个学生生成一份覆盖全图谱的诊断卷，并直接指派（学生端「我的作业」可见）。
     *
     * @param perNode      每个知识点最多抽几题，默认 1（诊断求「广」不求「深」），上限 3
     * @param maxQuestions 整卷题目上限，默认 20
     */
    @Transactional
    public Map<String, Object> generate(Long studentId, Integer perNode, Integer maxQuestions) {
        Student student = requireStudent(studentId);
        StudentGraph sg = latestStudentGraph(studentId);
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱，无法生成诊断卷");
        }

        int per = perNode == null ? 1 : Math.max(1, Math.min(3, perNode));
        int cap = maxQuestions == null ? 20 : Math.max(1, Math.min(60, maxQuestions));

        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        if (nodes.isEmpty()) {
            throw new BizException("图谱下没有知识点，无法生成诊断卷");
        }
        Map<Long, List<Question>> byNode = loadQuestionsByNode(nodes.stream().map(GraphNode::getId).toList());

        // 每个知识点抽最有区分度的题：难度 3 最接近「既非送分也非压轴」
        List<ExamQuestion> picked = new ArrayList<>();
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<Map<String, Object>> uncovered = new ArrayList<>();
        int order = 0;
        for (GraphNode node : nodes) {
            List<Question> pool = new ArrayList<>(byNode.getOrDefault(node.getId(), List.of()));
            if (pool.isEmpty()) {
                uncovered.add(Map.of("nodeId", node.getId(), "nodeName", node.getName()));
                continue;
            }
            pool.sort(Comparator.comparingInt(q -> Math.abs((q.getDifficulty() == null ? 3 : q.getDifficulty()) - 3)));
            int take = Math.min(per, pool.size());
            List<Map<String, Object>> qs = new ArrayList<>();
            for (int i = 0; i < take && picked.size() < cap; i++) {
                Question q = pool.get(i);
                ExamQuestion eq = new ExamQuestion();
                eq.setQuestionId(q.getId());
                eq.setNodeId(node.getId());
                eq.setSortOrder(order++);
                picked.add(eq);
                qs.add(Map.of("questionId", q.getId(), "stem", q.getStem(),
                        "difficulty", q.getDifficulty() == null ? 0 : q.getDifficulty()));
            }
            if (!qs.isEmpty()) {
                coverage.add(Map.of("nodeId", node.getId(), "nodeName", node.getName(),
                        "weight", node.getWeight() == null ? BigDecimal.ONE : node.getWeight(),
                        "questions", qs));
            }
        }
        if (picked.isEmpty()) {
            throw new BizException("题库中没有关联任何知识点的题目，无法生成诊断卷（请先录入题目并映射知识点）");
        }

        // 重新生成时清掉上一次「还没交」的诊断卷，避免学生端堆一堆
        purgeOpenDiagnosis(studentId);

        Graph graph = graphStore.findGraph(sg.getSourceGraphId());
        CurrentUser operator = UserContext.peek();
        Exam exam = new Exam();
        exam.setName("入学诊断 · " + student.getName());
        exam.setSource(Exam.SRC_DIAGNOSIS);
        exam.setStudentId(studentId);
        exam.setOrgId(student.getOrgId());
        if (graph != null) {
            exam.setSubject(graph.getSubject());
            exam.setTextbook(graph.getTextbook());
        }
        exam.setDescription("覆盖全部知识点的摸底测验，用于确立学习起点");
        exam.setCreatedBy(operator == null ? null : operator.userId());
        exam.setStatus("CONFIRMED");
        exam.setPublishedAt(LocalDateTime.now());
        exam.setPlan(writeJson(Map.of(
                "kind", "DIAGNOSIS",
                "coveredNodes", coverage.size(),
                "totalNodes", nodes.size(),
                "questionCount", picked.size(),
                "coverage", coverage,
                "uncovered", uncovered)));
        examMapper.insert(exam);

        for (ExamQuestion eq : picked) {
            eq.setExamId(exam.getId());
            examQuestionMapper.insert(eq);
        }

        ExamAssignment assignment = new ExamAssignment();
        assignment.setExamId(exam.getId());
        assignment.setStudentId(studentId);
        assignment.setStatus(ExamAssignment.ST_ASSIGNED);
        assignment.setPendingCount(0);
        assignmentMapper.insert(assignment);

        log.info("已为学生 #{}「{}」生成诊断卷 #{}：{} 题 / 覆盖 {}/{} 个知识点",
                studentId, student.getName(), exam.getId(), picked.size(), coverage.size(), nodes.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("studentId", studentId);
        result.put("studentName", student.getName());
        result.put("name", exam.getName());
        result.put("source", Exam.SRC_DIAGNOSIS);
        result.put("status", exam.getStatus());
        result.put("questionCount", picked.size());
        result.put("coveredNodes", coverage.size());
        result.put("totalNodes", nodes.size());
        result.put("uncovered", uncovered);
        result.put("coverage", coverage);
        return result;
    }

    // ==================== 诊断报告 ====================

    /**
     * 诊断报告：还没交卷时返回进度；交了卷返回逐知识点得分率与掌握度起点、
     * 薄弱 / 已掌握清单，以及「建议从这里开始学」的推荐。
     */
    public Map<String, Object> report(Long studentId) {
        requireStudent(studentId);
        Exam exam = latestDiagnosis(studentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        if (exam == null) {
            result.put("hasDiagnosis", false);
            result.put("completed", false);
            result.put("message", "还没有做过诊断，建议先完成一次入学诊断，系统才能给出学习起点");
            return result;
        }
        result.put("hasDiagnosis", true);
        result.put("examId", exam.getId());
        result.put("name", exam.getName());
        result.put("createdAt", exam.getCreatedAt());

        ExamAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<ExamAssignment>()
                .eq(ExamAssignment::getExamId, exam.getId())
                .eq(ExamAssignment::getStudentId, studentId)
                .last("limit 1"));
        String status = assignment == null ? null : assignment.getStatus();
        result.put("status", status);
        boolean submitted = assignment != null && !ExamAssignment.ST_ASSIGNED.equals(status);
        result.put("completed", submitted);

        List<ExamQuestion> eqs = examQuestionMapper.selectList(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, exam.getId())
                .orderByAsc(ExamQuestion::getSortOrder));
        result.put("questionCount", eqs.size());
        if (!submitted) {
            result.put("message", "诊断卷已就绪，完成交卷后即可看到诊断报告");
            return result;
        }

        result.put("totalScore", assignment.getTotalScore());
        result.put("fullScore", assignment.getFullScore());
        result.put("submittedAt", assignment.getSubmittedAt());

        // 逐知识点得分率
        Map<Long, AnswerRecord> records = new HashMap<>();
        answerRecordMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getAssignmentId, assignment.getId()))
                .forEach(r -> records.put(r.getQuestionId(), r));
        Map<Long, List<Double>> rateByNode = new HashMap<>();
        Map<Long, long[]> correctByNode = new HashMap<>();
        for (ExamQuestion eq : eqs) {
            AnswerRecord record = records.get(eq.getQuestionId());
            if (record == null || eq.getNodeId() == null || eq.getNodeId() <= 0) {
                continue;
            }
            double full = nz(record.getFullScore()).doubleValue();
            if (full <= 0) {
                continue;
            }
            double rate = nz(record.getScore()).doubleValue() / full;
            rateByNode.computeIfAbsent(eq.getNodeId(), k -> new ArrayList<>()).add(rate);
            long[] c = correctByNode.computeIfAbsent(eq.getNodeId(), k -> new long[2]);
            c[1]++;
            if (rate >= 0.6) {
                c[0]++;
            }
        }

        StudentGraph sg = latestStudentGraph(studentId);
        Map<Long, BigDecimal> masteryMap = new HashMap<>();
        if (sg != null) {
            studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                            .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                    .forEach(s -> masteryMap.put(s.getSourceNodeId(), s.getMastery()));
        }
        double weakTh = recommendService.configOf(exam.getOrgId()).weakThreshold();
        double solidTh = recommendService.configOf(exam.getOrgId()).solidThreshold();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Long, List<Double>> entry : rateByNode.entrySet()) {
            Long nodeId = entry.getKey();
            double rate = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            GraphNode node = graphStore.findNode(nodeId);
            long[] c = correctByNode.getOrDefault(nodeId, new long[]{0, 0});
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", nodeId);
            item.put("nodeName", node == null ? "知识点 #" + nodeId : node.getName());
            item.put("weight", node == null ? null : node.getWeight());
            item.put("questionCount", c[1]);
            item.put("correctCount", c[0]);
            item.put("rate", round4(rate));
            item.put("mastery", masteryMap.getOrDefault(nodeId, BigDecimal.ZERO));
            item.put("level", rate < weakTh ? "WEAK" : (rate >= solidTh ? "SOLID" : "MID"));
            items.add(item);
        }
        items.sort(Comparator.comparingDouble(m -> ((Number) m.get("rate")).doubleValue()));

        List<Map<String, Object>> weak = items.stream()
                .filter(i -> "WEAK".equals(i.get("level"))).toList();
        List<Map<String, Object>> solid = items.stream()
                .filter(i -> "SOLID".equals(i.get("level"))).toList();
        double avgRate = items.stream().mapToDouble(i -> ((Number) i.get("rate")).doubleValue())
                .average().orElse(0);

        result.put("items", items);
        result.put("weakNodes", weak);
        result.put("solidNodes", solid);
        result.put("avgRate", round4(avgRate));
        result.put("thresholds", Map.of("weak", weakTh, "solid", solidTh));
        result.put("uncoveredNodes", ((List<?>) readPlan(exam).getOrDefault("uncovered", List.of())).size());

        // 诊断刚初始化完掌握度，直接给出「从这里开始学」
        try {
            result.put("recommendations", recommendService.recommend(studentId, 5).stream()
                    .map(RecommendService.Recommendation::toMap).toList());
        } catch (Exception e) {
            log.warn("诊断报告生成推荐失败: {}", e.getMessage());
            result.put("recommendations", List.of());
        }
        return result;
    }

    /** 当前学生是否已完成过诊断（供任务流与首页使用） */
    public boolean completed(Long studentId) {
        Exam exam = latestDiagnosis(studentId);
        if (exam == null) {
            return false;
        }
        ExamAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<ExamAssignment>()
                .eq(ExamAssignment::getExamId, exam.getId())
                .eq(ExamAssignment::getStudentId, studentId)
                .last("limit 1"));
        return assignment != null && !ExamAssignment.ST_ASSIGNED.equals(assignment.getStatus());
    }

    /** 最近一次诊断卷（无论是否已交） */
    public Exam latestDiagnosis(Long studentId) {
        return examMapper.selectOne(new LambdaQueryWrapper<Exam>()
                .eq(Exam::getSource, Exam.SRC_DIAGNOSIS)
                .eq(Exam::getStudentId, studentId)
                .orderByDesc(Exam::getId)
                .last("limit 1"));
    }

    // ==================== 内部 ====================

    /** 清掉该学生「还没交卷」的诊断卷（重新生成时调用；已交卷的保留，作为掌握度起点凭据） */
    private void purgeOpenDiagnosis(Long studentId) {
        List<Exam> old = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                .eq(Exam::getSource, Exam.SRC_DIAGNOSIS)
                .eq(Exam::getStudentId, studentId));
        for (Exam exam : old) {
            ExamAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<ExamAssignment>()
                    .eq(ExamAssignment::getExamId, exam.getId())
                    .eq(ExamAssignment::getStudentId, studentId)
                    .last("limit 1"));
            if (assignment != null && ExamAssignment.ST_ASSIGNED.equals(assignment.getStatus())) {
                assignmentMapper.deleteById(assignment.getId());
                examQuestionMapper.delete(new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamId, exam.getId()));
                examMapper.deleteById(exam.getId());
            }
        }
    }

    private Map<Long, List<Question>> loadQuestionsByNode(List<Long> nodeIds) {
        Map<Long, List<Question>> map = new HashMap<>();
        if (nodeIds.isEmpty()) {
            return map;
        }
        for (QuestionNode link : questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().in(QuestionNode::getNodeId, nodeIds))) {
            Question q = questionMapper.selectById(link.getQuestionId());
            if (q != null) {
                map.computeIfAbsent(link.getNodeId(), k -> new ArrayList<>()).add(q);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPlan(Exam exam) {
        if (exam.getPlan() == null || exam.getPlan().isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(exam.getPlan(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private StudentGraph latestStudentGraph(Long studentId) {
        return studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
    }

    private Student requireStudent(Long studentId) {
        return studentAccessService.requireStudent(studentId);
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
