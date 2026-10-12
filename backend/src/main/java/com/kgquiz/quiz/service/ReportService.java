package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.AnswerRecord;
import com.kgquiz.quiz.entity.Exam;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.AnswerRecordMapper;
import com.kgquiz.quiz.mapper.ExamMapper;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学情分析：把散在 answer_record / student_node_state 里的原始数据，聚合成
 * 老师能直接看的「错题本」和「学情报告」。
 *
 * <p>错题本回答"错在哪"，学情报告回答"整体什么水平、下一步该补什么"。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StudentMapper studentMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final GraphStore graphStore;
    private final QuestionMapper questionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final ExamMapper examMapper;
    private final KgProperties kg;

    // ---------------- 错题本 ----------------

    public Map<String, Object> wrongQuestions(Long studentId) {
        Student student = requireStudent(studentId);
        List<AnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .orderByDesc(AnswerRecord::getId));

        Map<Long, List<AnswerRecord>> byQuestion = new LinkedHashMap<>();
        for (AnswerRecord r : records) {
            byQuestion.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int totalWrong = 0;
        for (Map.Entry<Long, List<AnswerRecord>> entry : byQuestion.entrySet()) {
            List<AnswerRecord> list = entry.getValue();
            List<AnswerRecord> wrongs = list.stream().filter(this::isWrong).toList();
            if (wrongs.isEmpty()) {
                continue;
            }
            totalWrong += wrongs.size();
            Question q = questionMapper.selectById(entry.getKey());
            AnswerRecord latest = wrongs.get(0); // 已按 id 倒序
            GraphNode node = latest.getNodeId() == null ? null : graphStore.findNode(latest.getNodeId());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", entry.getKey());
            item.put("stem", q == null ? "" : q.getStem());
            item.put("questionType", q == null ? "" : q.getQuestionType());
            item.put("options", q == null ? null : parseOptions(q.getOptions()));
            item.put("answer", q == null ? "" : q.getAnswer());
            item.put("analysis", q == null ? null : q.getAnalysis());
            item.put("difficulty", q == null ? null : q.getDifficulty());
            item.put("fullScore", q == null ? null : q.getScore());
            item.put("nodeId", latest.getNodeId());
            item.put("nodeName", node == null ? "" : node.getName());
            item.put("wrongCount", wrongs.size());
            item.put("attemptCount", list.size());
            item.put("lastWrongAt", latest.getCreatedAt());
            item.put("lastScore", latest.getScore());
            items.add(item);
        }

        items.sort(Comparator
                .comparingInt((Map<String, Object> m) -> (int) m.get("wrongCount")).reversed()
                .thenComparing(m -> String.valueOf(m.get("lastWrongAt")), Comparator.reverseOrder()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("studentName", student.getName());
        result.put("wrongTotal", totalWrong);
        result.put("questionTotal", items.size());
        result.put("items", items);
        return result;
    }

    // ---------------- 学情报告 ----------------

    public Map<String, Object> report(Long studentId) {
        Student student = requireStudent(studentId);
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱");
        }
        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        Map<Long, StudentNodeState> stateMap = new HashMap<>();
        studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .forEach(s -> stateMap.put(s.getSourceNodeId(), s));

        List<AnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>().eq(AnswerRecord::getStudentId, studentId));

        Map<Long, List<AnswerRecord>> recordsByNode = records.stream()
                .collect(Collectors.groupingBy(AnswerRecord::getNodeId));

        double weakTh = kg.getExam().getWeakThreshold();
        double solidTh = kg.getExam().getSolidThreshold();

        // ---- 知识点明细 ----
        List<Map<String, Object>> nodeStats = new ArrayList<>();
        int mastered = 0, learning = 0, weak = 0, untouched = 0;
        for (GraphNode node : nodes) {
            StudentNodeState state = stateMap.get(node.getId());
            double mastery = state == null || state.getMastery() == null
                    ? 0.0 : state.getMastery().doubleValue();
            List<AnswerRecord> list = recordsByNode.getOrDefault(node.getId(), List.of());
            long correct = list.stream().filter(r -> !isWrong(r)).count();
            double accuracy = list.isEmpty() ? 0.0 : (double) correct / list.size();

            String level;
            if (list.isEmpty() && mastery <= 0) {
                // 真正没碰过：既没有答题记录，也没有任何掌握度
                level = "未练习";
                untouched++;
            } else if (mastery >= solidTh) {
                level = "已掌握";
                mastered++;
            } else if (mastery < weakTh) {
                level = "薄弱";
                weak++;
            } else {
                level = "掌握中";
                learning++;
            }

            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("nodeId", node.getId());
            stat.put("nodeName", node.getName());
            stat.put("weight", node.getWeight());
            stat.put("mastery", round4(mastery));
            stat.put("level", level);
            stat.put("answerCount", list.size());
            stat.put("correctCount", correct);
            stat.put("accuracy", round4(accuracy));
            stat.put("lastPracticedAt", state == null ? null : state.getLastPracticedAt());
            nodeStats.add(stat);
        }
        nodeStats.sort(Comparator.comparingDouble(m ->
                ((Number) ((Map<String, Object>) m).get("mastery")).doubleValue()));

        // ---- 总览 ----
        double fullSum = records.stream().mapToDouble(r -> r.getFullScore() == null ? 0 : r.getFullScore().doubleValue()).sum();
        double scoreSum = records.stream().mapToDouble(r -> r.getScore() == null ? 0 : r.getScore().doubleValue()).sum();
        long correctTotal = records.stream().filter(r -> !isWrong(r)).count();
        double avgMastery = nodeStats.stream()
                .mapToDouble(m -> ((Number) m.get("mastery")).doubleValue()).average().orElse(0);
        double weightSum = nodeStats.stream()
                .mapToDouble(m -> ((BigDecimal) m.get("weight") == null ? 1.0
                        : ((BigDecimal) m.get("weight")).doubleValue())).sum();
        double weightedMastery = weightSum == 0 ? 0 : nodeStats.stream()
                .mapToDouble(m -> ((Number) m.get("mastery")).doubleValue()
                        * (((BigDecimal) m.get("weight")) == null ? 1.0
                        : ((BigDecimal) m.get("weight")).doubleValue()))
                .sum() / weightSum;

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("nodeTotal", nodes.size());
        overview.put("mastered", mastered);
        overview.put("learning", learning);
        overview.put("weak", weak);
        overview.put("untouched", untouched);
        overview.put("answerTotal", records.size());
        overview.put("correctTotal", correctTotal);
        overview.put("accuracy", round4(fullSum == 0 ? 0 : scoreSum / fullSum));
        overview.put("avgMastery", round4(avgMastery));
        overview.put("weightedMastery", round4(weightedMastery));
        overview.put("thresholds", Map.of("weak", weakTh, "solid", solidTh));

        // ---- 历次练习趋势 ----
        Map<Long, List<AnswerRecord>> byExam = records.stream()
                .filter(r -> r.getExamId() != null)
                .collect(Collectors.groupingBy(AnswerRecord::getExamId));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<Long, List<AnswerRecord>> entry : byExam.entrySet()) {
            Exam exam = examMapper.selectById(entry.getKey());
            if (exam == null) {
                continue;
            }
            List<AnswerRecord> list = entry.getValue();
            double f = list.stream().mapToDouble(r -> r.getFullScore() == null ? 0 : r.getFullScore().doubleValue()).sum();
            double s = list.stream().mapToDouble(r -> r.getScore() == null ? 0 : r.getScore().doubleValue()).sum();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("examId", exam.getId());
            t.put("createdAt", exam.getCreatedAt());
            t.put("questionCount", list.size());
            t.put("accuracy", round4(f == 0 ? 0 : s / f));
            trend.add(t);
        }
        trend.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));

        // ---- 薄弱知识点 + 建议（含前置提示） ----
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (NodeRelation r : graphStore.listRelationsByType(sg.getSourceGraphId(), RelationType.PREREQUISITE)) {
            prereqOf.computeIfAbsent(r.getToNodeId(), k -> new ArrayList<>()).add(r.getFromNodeId());
        }
        Map<Long, Map<String, Object>> statById = new HashMap<>();
        nodeStats.forEach(m -> statById.put((Long) m.get("nodeId"), m));

        List<Map<String, Object>> advice = new ArrayList<>();
        nodeStats.stream()
                .filter(m -> "薄弱".equals(m.get("level")) || "未练习".equals(m.get("level")))
                .limit(5)
                .forEach(m -> {
                    Long nodeId = (Long) m.get("nodeId");
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("nodeId", nodeId);
                    a.put("nodeName", m.get("nodeName"));
                    a.put("mastery", m.get("mastery"));
                    a.put("level", m.get("level"));
                    List<String> missingPrereqs = new ArrayList<>();
                    for (Long preId : prereqOf.getOrDefault(nodeId, List.of())) {
                        Map<String, Object> pre = statById.get(preId);
                        if (pre != null && (!"已掌握".equals(pre.get("level")))) {
                            missingPrereqs.add(String.valueOf(pre.get("nodeName")));
                        }
                    }
                    a.put("unmasteredPrereqs", missingPrereqs);
                    a.put("suggestion", missingPrereqs.isEmpty()
                            ? "直接安排该知识点的练习"
                            : "建议先补前置：" + String.join("、", missingPrereqs));
                    advice.add(a);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("studentName", student.getName());
        result.put("graphId", sg.getSourceGraphId());
        result.put("generatedAt", LocalDateTime.now().toString());
        result.put("overview", overview);
        result.put("nodeStats", nodeStats);
        result.put("trend", trend);
        result.put("advice", advice);
        result.put("wrongTotal", records.stream().filter(this::isWrong).count());
        result.put("lastPracticedDate", records.stream()
                .map(AnswerRecord::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(LocalDateTime::toLocalDate)
                .map(Object::toString).orElse(null));
        return result;
    }

    // ---------------- 内部 ----------------

    private Student requireStudent(Long studentId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException("学生不存在: " + studentId);
        }
        // 跨机构一律 403：报告与错题本同样受租户边界约束
        if (student.getOrgId() != null) {
            UserContext.checkOrgScope(student.getOrgId());
        } else if (!UserContext.require().isAdmin()) {
            throw new BizException("学生不存在: " + studentId);
        }
        return student;
    }

    /** 得分率 < 100% 即视为错题（含部分给分的填空/简答） */
    private boolean isWrong(AnswerRecord r) {
        double full = r.getFullScore() == null ? 0 : r.getFullScore().doubleValue();
        double score = r.getScore() == null ? 0 : r.getScore().doubleValue();
        if (full <= 0) {
            return true;
        }
        return score / full < 0.999;
    }

    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return null;
        }
    }
}
