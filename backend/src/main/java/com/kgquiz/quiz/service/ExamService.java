package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.dto.PlanGenerateReq;
import com.kgquiz.quiz.entity.Exam;
import com.kgquiz.quiz.entity.ExamAssignment;
import com.kgquiz.quiz.entity.ExamQuestion;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.entity.RelationType;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.ExamAssignmentMapper;
import com.kgquiz.quiz.mapper.ExamMapper;
import com.kgquiz.quiz.mapper.ExamQuestionMapper;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自适应出题引擎：
 * 1. 低掌握度优先补弱 + 巩固配额防遗忘 + 前置知识点约束
 * 2. 生成结构化出题方案，确认后成为练习
 * 3. 答题后回写掌握度，形成闭环
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAssignmentMapper examAssignmentMapper;
    private final GraphStore graphStore;
    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final StudentMapper studentMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final KgProperties kg;
    private final RecommendService recommendService;
    private final StudentAccessService studentAccessService;

    /** 打分权重：与 RecommendService 默认规则一致，公式真源统一在 RecommendService.priorityScore */
    private static final double MASTERY_WEIGHT = 0.7;
    private static final double IMPORTANCE_WEIGHT = 0.3;
    private static final double PREREQ_BOOST = 0.15;

    /**
     * 生成出题方案（PENDING，确认后才可答题）
     */
    public Map<String, Object> generatePlan(PlanGenerateReq req) throws Exception {
        Student student = studentAccessService.requireStudent(req.studentId());
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, req.studentId())
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱，无法出题");
        }

        int total = req.totalQuestions() == null ? 10 : Math.max(1, Math.min(50, req.totalQuestions()));
        int perNodeLimit = req.perNodeLimit() == null ? 3 : Math.max(1, Math.min(10, req.perNodeLimit()));

        // 1. 学生图谱节点 + 掌握度 + 权重
        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        Map<Long, Double> masteryMap = new HashMap<>();
        studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .forEach(s -> masteryMap.put(s.getSourceNodeId(),
                        s.getMastery() == null ? 0.0 : s.getMastery().doubleValue()));
        double maxWeight = nodes.stream()
                .map(n -> n.getWeight() == null ? 1.0 : n.getWeight().doubleValue())
                .max(Double::compare).orElse(1.0);

        // 2. 题库按节点分组
        Map<Long, List<Question>> questionsByNode = loadQuestionsByNode(
                nodes.stream().map(GraphNode::getId).toList());
        Set<Long> nodesWithQuestions = new HashSet<>(questionsByNode.keySet());

        // 3. 前置关系：to -> [from]，即每个节点的前置节点（关系类型下推到 Cypher）
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (NodeRelation r : graphStore.listRelationsByType(sg.getSourceGraphId(), RelationType.PREREQUISITE)) {
            prereqOf.computeIfAbsent(r.getToNodeId(), k -> new ArrayList<>()).add(r.getFromNodeId());
        }

        double weakTh = kg.getExam().getWeakThreshold();
        double solidTh = kg.getExam().getSolidThreshold();

        // 4. 分类 + 优先级
        List<NodeCtx> weakPool = new ArrayList<>();
        List<NodeCtx> midPool = new ArrayList<>();
        List<NodeCtx> solidPool = new ArrayList<>();
        Map<Long, String> reasonExtra = new HashMap<>();

        for (GraphNode node : nodes) {
            double mastery = masteryMap.getOrDefault(node.getId(), 0.0);
            double weight = node.getWeight() == null ? 1.0 : node.getWeight().doubleValue();
            if (!nodesWithQuestions.contains(node.getId())) {
                continue;
            }
            NodeCtx ctx = new NodeCtx(node, mastery, weight);
            if (mastery < weakTh) {
                weakPool.add(ctx);
            } else if (mastery >= solidTh) {
                solidPool.add(ctx);
            } else {
                midPool.add(ctx);
            }
        }

        // 前置约束：薄弱节点若前置也薄弱，前置优先级提升
        // 注意：不能在遍历 weakPool 时直接 add，先收集再合并
        Set<Long> boosted = new HashSet<>();
        List<NodeCtx> promoted = new ArrayList<>();
        for (NodeCtx ctx : weakPool) {
            for (Long preId : prereqOf.getOrDefault(ctx.node.getId(), List.of())) {
                NodeCtx pre = findByNode(weakPool, preId);
                if (pre == null) {
                    pre = findByNode(midPool, preId);
                    if (pre != null && midPool.remove(pre)) {
                        promoted.add(pre);
                    }
                }
                if (pre != null) {
                    boosted.add(pre.node.getId());
                    reasonExtra.put(pre.node.getId(), "作为前置知识被优先安排");
                }
            }
        }
        weakPool.addAll(promoted);

        // 优先级 = (1-掌握度)*0.7 + 归一化权重*0.3
        // 公式已抽到 RecommendService.priorityScore，出题与「学什么」推荐共用同一实现
        RecommendService.Config cfg = new RecommendService.Config(
                MASTERY_WEIGHT, IMPORTANCE_WEIGHT, weakTh, solidTh,
                PREREQ_BOOST, kg.getExam().getConsolidateRatio(), 5);
        Comparator<NodeCtx> byPriority = Comparator.comparingDouble(
                (NodeCtx c) -> recommendService.priorityScore(c.mastery, c.weight, maxWeight, cfg))
                .reversed();
        weakPool.sort(byPriority);

        // 5. 题量分配
        int consolidateQuota = (int) Math.round(total * cfg.consolidateRatio());
        int mainQuota = total - consolidateQuota;

        List<NodeCtx> selected = new ArrayList<>();
        Map<NodeCtx, Integer> quotaMap = new LinkedHashMap<>();

        int remaining = mainQuota;
        for (NodeCtx ctx : weakPool) {
            if (remaining <= 0) {
                break;
            }
            int avail = Math.min(perNodeLimit, questionsByNode.get(ctx.node.getId()).size());
            int take = Math.min(avail, remaining);
            if (take <= 0) {
                continue;
            }
            quotaMap.put(ctx, take);
            remaining -= take;
        }
        // 薄弱节点不够时从中等补齐
        if (remaining > 0) {
            List<NodeCtx> midSorted = new ArrayList<>(midPool);
            midSorted.sort(byPriority);
            for (NodeCtx ctx : midSorted) {
                if (remaining <= 0) {
                    break;
                }
                int avail = Math.min(perNodeLimit, questionsByNode.get(ctx.node.getId()).size());
                int take = Math.min(avail, remaining);
                if (take <= 0) {
                    continue;
                }
                quotaMap.put(ctx, take);
                remaining -= take;
            }
        }

        // 巩固配额
        List<NodeCtx> solidShuffled = new ArrayList<>(solidPool);
        Collections.shuffle(solidShuffled);
        int cRemaining = total - quotaMap.values().stream().mapToInt(Integer::intValue).sum();
        for (NodeCtx ctx : solidShuffled) {
            if (cRemaining <= 0) {
                break;
            }
            int avail = Math.min(2, questionsByNode.get(ctx.node.getId()).size());
            int take = Math.min(avail, cRemaining);
            quotaMap.put(ctx, take);
            cRemaining -= take;
        }

        // 6. 每个节点按难度选题
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<NodeCtx, Integer> entry : quotaMap.entrySet()) {
            NodeCtx ctx = entry.getKey();
            int quota = entry.getValue();
            List<Question> pool = new ArrayList<>(questionsByNode.get(ctx.node.getId()));
            // 薄弱选易题，巩固选难题
            int targetDifficulty = ctx.mastery < weakTh ? 2 : (ctx.mastery >= solidTh ? 4 : 3);
            pool.sort(Comparator.comparingInt(q -> Math.abs(q.getDifficulty() - targetDifficulty)));
            List<Question> chosen = pool.subList(0, Math.min(quota, pool.size()));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", ctx.node.getId());
            item.put("nodeName", ctx.node.getName());
            item.put("mastery", round4(ctx.mastery));
            item.put("weight", ctx.weight);
            String reason = buildReason(ctx, weakTh, solidTh, reasonExtra.get(ctx.node.getId()));
            item.put("reason", reason);
            List<Map<String, Object>> qs = new ArrayList<>();
            for (Question q : chosen) {
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("questionId", q.getId());
                qm.put("stem", q.getStem());
                qm.put("questionType", q.getQuestionType());
                qm.put("difficulty", q.getDifficulty());
                qm.put("score", q.getScore());
                qs.add(qm);
            }
            item.put("questions", qs);
            items.add(item);
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("studentId", req.studentId());
        plan.put("studentName", student.getName());
        plan.put("generatedAt", LocalDateTime.now().toString());
        plan.put("totalQuestions", items.stream()
                .mapToInt(i -> ((List<?>) i.get("questions")).size()).sum());
        plan.put("thresholds", Map.of("weak", weakTh, "solid", solidTh,
                "learningRate", kg.getExam().getLearningRate()));
        plan.put("items", items);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("weakNodes", weakPool.size());
        summary.put("solidNodes", solidPool.size());
        summary.put("nodesWithoutQuestions", nodes.stream()
                .filter(n -> !nodesWithQuestions.contains(n.getId())).count());
        summary.put("prereqBoosted", boosted);
        plan.put("summary", summary);

        // 7. 方案落库（PENDING）
        Graph graph = graphStore.findGraph(sg.getSourceGraphId());
        CurrentUser operator = UserContext.peek();
        Exam exam = new Exam();
        exam.setStudentId(req.studentId());
        exam.setOrgId(student.getOrgId());
        exam.setName("自适应练习 · " + student.getName());
        exam.setSource(Exam.SRC_ADAPTIVE);
        if (graph != null) {
            exam.setSubject(graph.getSubject());
            exam.setTextbook(graph.getTextbook());
        }
        exam.setDescription("由自适应出题引擎按当前掌握度生成");
        exam.setCreatedBy(operator == null ? null : operator.userId());
        exam.setPlan(MAPPER.writeValueAsString(plan));
        exam.setStatus("PENDING");
        examMapper.insert(exam);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("status", exam.getStatus());
        result.put("plan", plan);
        return result;
    }

    /** 确认方案 → 生成练习，并自动指派给该学生（学生端「我的作业」据此可见） */
    @Transactional
    public Map<String, Object> confirmPlan(Long examId) throws Exception {
        Exam exam = getExam(examId);
        if (!"PENDING".equals(exam.getStatus())) {
            throw new BizException("方案状态为 " + exam.getStatus() + "，无需确认");
        }
        Map<String, Object> plan = MAPPER.readValue(exam.getPlan(), Map.class);
        int order = 0;
        for (Object itemObj : (List<?>) plan.get("items")) {
            Map<?, ?> item = (Map<?, ?>) itemObj;
            Long nodeId = ((Number) item.get("nodeId")).longValue();
            for (Object qObj : (List<?>) item.get("questions")) {
                Long questionId = ((Number) ((Map<?, ?>) qObj).get("questionId")).longValue();
                ExamQuestion eq = new ExamQuestion();
                eq.setExamId(examId);
                eq.setQuestionId(questionId);
                eq.setNodeId(nodeId);
                eq.setSortOrder(order++);
                examQuestionMapper.insert(eq);
            }
        }
        exam.setStatus("CONFIRMED");
        exam.setPublishedAt(LocalDateTime.now());
        examMapper.updateById(exam);

        // 自适应练习同样走「指派」模型，这样学生端只有一个作业列表
        if (exam.getStudentId() != null) {
            Long exists = examAssignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                    .eq(ExamAssignment::getExamId, examId)
                    .eq(ExamAssignment::getStudentId, exam.getStudentId()));
            if (exists == null || exists == 0) {
                ExamAssignment assignment = new ExamAssignment();
                assignment.setExamId(examId);
                assignment.setStudentId(exam.getStudentId());
                assignment.setStatus(ExamAssignment.ST_ASSIGNED);
                assignment.setPendingCount(0);
                examAssignmentMapper.insert(assignment);
            }
        }
        return examDetail(examId);
    }

    /**
     * 给学生即时生成一份「针对某个知识点」的专项练习，并直接指派给该学生。
     *
     * <p>这是学习任务流里「练 / 复习」任务的入口：学生点「开始练习」，
     * 后端现出一份小题量的卷子，做完走 EMA 更新掌握度，闭环就闭合了。</p>
     *
     * @param nodeId 目标知识点；为空则不限定（不推荐，仅作兜底）
     * @param count  题量，默认 5
     */
    @Transactional
    public Map<String, Object> generatePracticeForStudent(Long studentId, Long nodeId, Integer count) {
        Student student = studentAccessService.requireStudent(studentId);
        int n = count == null ? 5 : Math.max(1, Math.min(20, count));

        GraphNode node = nodeId == null ? null : graphStore.findNode(nodeId);
        List<Question> pool = new ArrayList<>();
        if (nodeId != null) {
            for (QuestionNode link : questionNodeMapper.selectList(new LambdaQueryWrapper<QuestionNode>()
                    .eq(QuestionNode::getNodeId, nodeId))) {
                Question q = questionMapper.selectById(link.getQuestionId());
                if (q != null) {
                    pool.add(q);
                }
            }
        }
        if (pool.isEmpty()) {
            StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                    .eq(StudentGraph::getStudentId, studentId)
                    .orderByDesc(StudentGraph::getId).last("limit 1"));
            if (sg == null) {
                throw new BizException("该学生尚未复制图谱，无法出题");
            }
            Map<Long, Question> uniq = new LinkedHashMap<>();
            loadQuestionsByNode(graphStore.listNodes(sg.getSourceGraphId()).stream()
                            .map(GraphNode::getId).toList())
                    .forEach((k, v) -> v.forEach(q -> uniq.putIfAbsent(q.getId(), q)));
            pool = new ArrayList<>(uniq.values());
        }
        if (pool.isEmpty()) {
            throw new BizException("题库里还没有可用的题目，请联系老师补充");
        }

        // 取中间难度的题：既不送分也不劝退
        List<Question> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingInt(q -> Math.abs((q.getDifficulty() == null ? 3 : q.getDifficulty()) - 3)));
        List<Question> chosen = sorted.subList(0, Math.min(n, sorted.size()));

        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId).last("limit 1"));
        Graph graph = sg == null ? null : graphStore.findGraph(sg.getSourceGraphId());

        Exam exam = new Exam();
        exam.setName(node == null ? "专项练习" : "专项练习 · " + node.getName());
        exam.setSource(Exam.SRC_ADAPTIVE);
        exam.setStudentId(studentId);
        exam.setOrgId(student.getOrgId());
        if (graph != null) {
            exam.setSubject(graph.getSubject());
            exam.setTextbook(graph.getTextbook());
        }
        exam.setDescription(node == null ? "按当前掌握度生成的练习"
                : "针对「" + node.getName() + "」的专项练习");
        exam.setCreatedBy(null);
        exam.setStatus("CONFIRMED");
        exam.setPublishedAt(LocalDateTime.now());
        exam.setPlan(writeJson(Map.of(
                "kind", "PRACTICE",
                "nodeId", nodeId == null ? -1 : nodeId,
                "nodeName", node == null ? "" : node.getName(),
                "questionCount", chosen.size())));
        examMapper.insert(exam);

        int order = 0;
        for (Question q : chosen) {
            ExamQuestion eq = new ExamQuestion();
            eq.setExamId(exam.getId());
            eq.setQuestionId(q.getId());
            eq.setNodeId(nodeId == null ? firstNodeOfQuestion(q.getId()) : nodeId);
            eq.setSortOrder(order++);
            examQuestionMapper.insert(eq);
        }
        ExamAssignment assignment = new ExamAssignment();
        assignment.setExamId(exam.getId());
        assignment.setStudentId(studentId);
        assignment.setStatus(ExamAssignment.ST_ASSIGNED);
        assignment.setPendingCount(0);
        examAssignmentMapper.insert(assignment);

        log.info("学生 #{} 发起专项练习 #{}（知识点 {}，{} 题）",
                studentId, exam.getId(), nodeId, chosen.size());
        return examDetail(exam.getId());
    }

    /** 练习列表 */
    /** 练习归属的学生 id（供越权校验使用） */
    public Long ownerOf(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new com.kgquiz.quiz.common.BizException("练习不存在: " + examId);
        }
        UserContext.checkOrgScope(exam.getOrgId());
        return exam.getStudentId();
    }

    /** 自适应练习列表（教师组卷走 AssignmentService，不混在这里） */
    public List<Exam> listExams(Long studentId) {
        LambdaQueryWrapper<Exam> wrapper = new LambdaQueryWrapper<Exam>()
                .eq(Exam::getSource, Exam.SRC_ADAPTIVE)
                .eq(studentId != null, Exam::getStudentId, studentId)
                .orderByDesc(Exam::getId);
        Long scope = UserContext.scopeOrgId();
        if (scope != null) {
            wrapper.eq(Exam::getOrgId, scope);
        }
        return examMapper.selectList(wrapper);
    }

    /** 练习详情（含题目全量信息与归属知识点） */
    public Map<String, Object> examDetail(Long examId) {
        Exam exam = getExam(examId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("studentId", exam.getStudentId());
        result.put("status", exam.getStatus());
        result.put("createdAt", exam.getCreatedAt());
        try {
            result.put("plan", MAPPER.readValue(exam.getPlan(), Map.class));
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        List<ExamQuestion> eqs = examQuestionMapper.selectList(
                new LambdaQueryWrapper<ExamQuestion>()
                        .eq(ExamQuestion::getExamId, examId)
                        .orderByAsc(ExamQuestion::getSortOrder));
        for (ExamQuestion eq : eqs) {
            Question q = questionMapper.selectById(eq.getQuestionId());
            if (q == null) {
                continue;
            }
            GraphNode node = graphStore.findNode(eq.getNodeId());
            Map<String, Object> qm = new LinkedHashMap<>();
            qm.put("questionId", q.getId());
            qm.put("stem", q.getStem());
            qm.put("options", parseOptions(q.getOptions()));
            qm.put("questionType", q.getQuestionType());
            qm.put("answer", q.getAnswer());
            qm.put("analysis", q.getAnalysis());
            qm.put("score", q.getScore());
            qm.put("difficulty", q.getDifficulty());
            qm.put("nodeId", eq.getNodeId());
            qm.put("nodeName", node == null ? "" : node.getName());
            questions.add(qm);
        }
        result.put("questions", questions);
        return result;
    }

    /** 学生掌握度总览 */
    public Map<String, Object> masteryOverview(Long studentId) {
        Student owner = studentAccessService.requireStudent(studentId);
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        if (sg == null) {
            throw new BizException("该学生尚未复制图谱");
        }
        List<GraphNode> nodes = graphStore.listNodes(sg.getSourceGraphId());
        Map<Long, StudentNodeState> states = new HashMap<>();
        studentNodeStateMapper.selectList(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId()))
                .forEach(s -> states.put(s.getSourceNodeId(), s));

        List<Map<String, Object>> nodeStats = new ArrayList<>();
        for (GraphNode node : nodes) {
            StudentNodeState state = states.get(node.getId());
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("nodeId", node.getId());
            stat.put("nodeName", node.getName());
            stat.put("weight", node.getWeight());
            stat.put("mastery", state == null ? 0 : state.getMastery());
            stat.put("lastPracticedAt", state == null ? null : state.getLastPracticedAt());
            nodeStats.add(stat);
        }
        nodeStats.sort(Comparator.comparingDouble(s ->
                ((Number) ((Map<String, Object>) s).get("mastery")).doubleValue()));

        double avg = nodeStats.stream()
                .mapToDouble(s -> ((Number) s.get("mastery")).doubleValue()).average().orElse(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("avgMastery", round4(avg));
        result.put("nodes", nodeStats);
        return result;
    }

    // ---------------- 内部 ----------------

    /**
     * 数据自愈：早期版本的自适应练习没有「指派」记录，学生端「我的作业」会看不到。
     * 启动时为这类历史数据补建指派，幂等。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillAdaptiveAssignments() {
        try {
            List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                    .eq(Exam::getSource, Exam.SRC_ADAPTIVE)
                    .eq(Exam::getStatus, "CONFIRMED")
                    .isNotNull(Exam::getStudentId));
            int created = 0;
            for (Exam exam : exams) {
                // 顺带回填早期数据缺失的名称
                if (exam.getName() == null || exam.getName().isBlank()) {
                    Student student = studentMapper.selectById(exam.getStudentId());
                    exam.setName("自适应练习 · " + (student == null
                            ? "#" + exam.getStudentId() : student.getName()));
                    examMapper.updateById(exam);
                }
                Long exists = examAssignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, exam.getId())
                        .eq(ExamAssignment::getStudentId, exam.getStudentId()));
                if (exists != null && exists > 0) {
                    continue;
                }
                ExamAssignment assignment = new ExamAssignment();
                assignment.setExamId(exam.getId());
                assignment.setStudentId(exam.getStudentId());
                assignment.setStatus(ExamAssignment.ST_ASSIGNED);
                assignment.setPendingCount(0);
                examAssignmentMapper.insert(assignment);
                created++;
            }
            if (created > 0) {
                log.warn("已为 {} 份历史自适应练习补建指派记录", created);
            }
        } catch (Exception e) {
            log.error("补建历史练习指派失败", e);
        }
    }

    private Map<Long, List<Question>> loadQuestionsByNode(List<Long> nodeIds) {
        Map<Long, List<Question>> map = new HashMap<>();
        if (nodeIds.isEmpty()) {
            return map;
        }
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().in(QuestionNode::getNodeId, nodeIds));
        for (QuestionNode link : links) {
            Question q = questionMapper.selectById(link.getQuestionId());
            if (q != null) {
                map.computeIfAbsent(link.getNodeId(), k -> new ArrayList<>()).add(q);
            }
        }
        return map;
    }

    private String buildReason(NodeCtx ctx, double weakTh, double solidTh, String extra) {
        String base;
        if (ctx.mastery < weakTh) {
            base = String.format("薄弱知识点（掌握度 %.2f），优先补强", ctx.mastery);
        } else if (ctx.mastery >= solidTh) {
            base = String.format("已掌握（掌握度 %.2f），巩固防遗忘", ctx.mastery);
        } else {
            base = String.format("掌握中（掌握度 %.2f），继续强化", ctx.mastery);
        }
        return extra == null ? base : base + "；" + extra;
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

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("出题方案序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** 题目关联的第一个知识点（用于不指定知识点时回填卷内题目的归属） */
    private Long firstNodeOfQuestion(Long questionId) {        QuestionNode link = questionNodeMapper.selectOne(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getQuestionId, questionId).last("limit 1"));
        return link == null || link.getNodeId() == null ? 0L : link.getNodeId();
    }

    private NodeCtx findByNode(List<NodeCtx> pool, Long nodeId) {        for (NodeCtx ctx : pool) {
            if (ctx.node.getId().equals(nodeId)) {
                return ctx;
            }
        }
        return null;
    }

    private Exam getExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BizException("练习不存在: " + examId);
        }
        UserContext.checkOrgScope(exam.getOrgId());
        return exam;
    }

    /** 出题引擎内部节点上下文 */
    private record NodeCtx(GraphNode node, double mastery, double weight) {
    }
}
