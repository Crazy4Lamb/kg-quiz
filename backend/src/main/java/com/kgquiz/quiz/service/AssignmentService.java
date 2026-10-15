package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.dto.AssignmentSubmitReq;
import com.kgquiz.quiz.dto.GradeReq;
import com.kgquiz.quiz.dto.PaperAssignReq;
import com.kgquiz.quiz.dto.PaperCreateReq;
import com.kgquiz.quiz.entity.AnswerRecord;
import com.kgquiz.quiz.entity.Exam;
import com.kgquiz.quiz.entity.ExamAssignment;
import com.kgquiz.quiz.entity.ExamQuestion;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.SysUser;
import com.kgquiz.quiz.mapper.AnswerRecordMapper;
import com.kgquiz.quiz.mapper.ExamAssignmentMapper;
import com.kgquiz.quiz.mapper.ExamMapper;
import com.kgquiz.quiz.mapper.ExamQuestionMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作业：教师组卷 → 定向发给学生 → 学生作答 → 机器自动批改 → 教师复核改分。
 *
 * <p>与 {@link ExamService}（自适应引擎）的分工：本服务管"卷子发给谁、谁做了、批了多少分"，
 * 自适应引擎只管"给某个学生生成什么题"。两边共用 exam / exam_question / answer_record 三张表，
 * 用 {@code exam.source} 区分。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExamMapper examMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final ExamAssignmentMapper assignmentMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final GraphStore graphStore;
    private final StudentMapper studentMapper;
    private final SysUserMapper sysUserMapper;
    private final MasteryService masteryService;
    private final LearningTaskService learningTaskService;
    private final LearningRecordService learningRecordService;
    private final StudentAccessService studentAccessService;

    // ==================== 组卷 ====================

    /** 教师从题库挑题组成一份卷子 */
    @Transactional
    public Map<String, Object> createPaper(PaperCreateReq req, CurrentUser operator) {
        List<Long> questionIds = req.questionIds().stream().filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (questionIds.isEmpty()) {
            throw new BizException("至少选择一道题");
        }
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        List<Long> missing = questionIds.stream().filter(id -> !questionMap.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new BizException("题目不存在或已被删除：" + missing);
        }

        // 租户边界：只能挑选本机构的题目组卷，且一张卷子不能跨机构
        java.util.Set<Long> questionOrgIds = new java.util.LinkedHashSet<>();
        for (Question q : questionMap.values()) {
            if (q.getOrgId() != null) {
                UserContext.checkOrgScope(q.getOrgId());
                questionOrgIds.add(q.getOrgId());
            } else if (!operator.isAdmin()) {
                throw new BizException("题目不存在或已被删除：" + q.getId());
            }
        }
        if (questionOrgIds.size() > 1) {
            throw new BizException("所选题目分属不同机构，无法成卷");
        }

        Question first = questionMap.get(questionIds.get(0));
        Exam exam = new Exam();
        exam.setName(req.name().trim());
        exam.setSource(Exam.SRC_TEACHER);
        exam.setStudentId(null);
        exam.setOrgId(questionOrgIds.isEmpty() ? operator.orgId() : questionOrgIds.iterator().next());
        exam.setSubject(blankTo(req.subject(), first.getSubject()));
        exam.setTextbook(blankTo(req.textbook(), first.getTextbook()));
        exam.setDescription(blankTo(req.description(), ""));
        exam.setCreatedBy(operator.userId());
        exam.setStatus("CONFIRMED");
        exam.setPublishedAt(LocalDateTime.now());
        BigDecimal totalScore = questionIds.stream()
                .map(id -> nz(questionMap.get(id).getScore()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        exam.setPlan(writeJson(Map.of(
                "paperName", exam.getName(),
                "questionCount", questionIds.size(),
                "totalScore", totalScore)));
        examMapper.insert(exam);

        int order = 0;
        for (Long qid : questionIds) {
            ExamQuestion eq = new ExamQuestion();
            eq.setExamId(exam.getId());
            eq.setQuestionId(qid);
            eq.setNodeId(firstNodeOf(qid));
            eq.setSortOrder(order++);
            examQuestionMapper.insert(eq);
        }
        log.info("教师 {} 组卷「{}」#{}，共 {} 题", operator.username(), exam.getName(),
                exam.getId(), questionIds.size());
        return paperDetail(exam.getId(), operator, true);
    }

    /** 卷子列表：教师看自己出的，管理员看全部 */
    public List<Map<String, Object>> listPapers(CurrentUser operator, Long filterTeacherId) {
        LambdaQueryWrapper<Exam> wrapper = new LambdaQueryWrapper<Exam>()
                .eq(Exam::getSource, Exam.SRC_TEACHER)
                .orderByDesc(Exam::getId);
        // 租户边界：非平台管理员只看本机构的卷子
        Long scope = operator.scopeOrgId();
        if (scope != null) {
            wrapper.eq(Exam::getOrgId, scope);
        }
        if (!operator.isAdmin()) {
            wrapper.eq(Exam::getCreatedBy, operator.userId());
        } else if (filterTeacherId != null) {
            wrapper.eq(Exam::getCreatedBy, filterTeacherId);
        }
        List<Exam> exams = examMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Exam exam : exams) {
            result.add(paperSummary(exam));
        }
        return result;
    }

    /** 卷子详情（教师视角：带参考答案与全部指派） */
    public Map<String, Object> paperDetail(Long examId, CurrentUser operator, boolean includeAnswers) {
        Exam exam = requireOwnedPaper(examId, operator);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("name", exam.getName());
        result.put("source", exam.getSource());
        result.put("subject", exam.getSubject());
        result.put("textbook", exam.getTextbook());
        result.put("description", exam.getDescription());
        result.put("status", exam.getStatus());
        result.put("createdBy", exam.getCreatedBy());
        result.put("creatorName", displayNameOf(exam.getCreatedBy()));
        result.put("createdAt", exam.getCreatedAt());
        result.put("publishedAt", exam.getPublishedAt());

        List<Map<String, Object>> questions = new ArrayList<>();
        for (ExamQuestion eq : examQuestions(examId)) {
            questions.add(questionView(eq, includeAnswers));
        }
        result.put("questions", questions);
        result.put("totalScore", sumScore(questions));
        result.put("assignments", assignmentViews(examId));
        return result;
    }

    @Transactional
    public Map<String, Object> deletePaper(Long examId, CurrentUser operator) {
        Exam exam = requireOwnedPaper(examId, operator);
        long submitted = assignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                .eq(ExamAssignment::getExamId, examId)
                .ne(ExamAssignment::getStatus, ExamAssignment.ST_ASSIGNED));
        if (submitted > 0) {
            throw new BizException("已有 " + submitted + " 名学生提交，不能删除卷子（可先撤销未提交的指派）");
        }
        assignmentMapper.delete(new LambdaQueryWrapper<ExamAssignment>().eq(ExamAssignment::getExamId, examId));
        examQuestionMapper.delete(new LambdaQueryWrapper<ExamQuestion>().eq(ExamQuestion::getExamId, examId));
        examMapper.deleteById(exam.getId());
        log.info("卷子 #{}「{}」已删除", examId, exam.getName());
        return Map.of("deleted", true, "examId", examId);
    }

    // ==================== 指派 ====================

    /** 把卷子发给若干学生 */
    @Transactional
    public Map<String, Object> assign(Long examId, PaperAssignReq req, CurrentUser operator) {
        Exam exam = requireOwnedPaper(examId, operator);
        if (!"CONFIRMED".equals(exam.getStatus())) {
            throw new BizException("卷子状态为 " + exam.getStatus() + "，不能指派");
        }
        List<Long> studentIds = req.studentIds().stream().filter(java.util.Objects::nonNull)
                .distinct().toList();
        int created = 0;
        int skipped = 0;
        for (Long studentId : studentIds) {
            Student student = studentMapper.selectById(studentId);
            if (student == null) {
                throw new BizException("学生不存在: " + studentId);
            }
            Long exists = assignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                    .eq(ExamAssignment::getExamId, examId)
                    .eq(ExamAssignment::getStudentId, studentId));
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            ExamAssignment assignment = new ExamAssignment();
            assignment.setExamId(examId);
            assignment.setStudentId(studentId);
            assignment.setStatus(ExamAssignment.ST_ASSIGNED);
            assignment.setPendingCount(0);
            assignmentMapper.insert(assignment);
            created++;
        }
        log.info("卷子 #{} 指派 {} 人（跳过已指派 {} 人）", examId, created, skipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("assigned", created);
        result.put("skipped", skipped);
        result.put("assignments", assignmentViews(examId));
        return result;
    }

    /** 撤销指派：已提交/已批改的不能撤 */
    @Transactional
    public Map<String, Object> unassign(Long examId, Long studentId, CurrentUser operator) {
        requireOwnedPaper(examId, operator);
        ExamAssignment assignment = requireAssignment(examId, studentId);
        if (!ExamAssignment.ST_ASSIGNED.equals(assignment.getStatus())) {
            throw new BizException("该学生已提交答卷，不能撤销指派");
        }
        assignmentMapper.deleteById(assignment.getId());
        return Map.of("examId", examId, "studentId", studentId, "removed", true);
    }

    // ==================== 学生视角 ====================

    /** 我的作业：指派给自己、且卷子处于可作答状态的 */
    public List<Map<String, Object>> myAssignments(Long studentId) {
        studentAccessService.requireStudent(studentId);
        List<ExamAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getStudentId, studentId)
                        .orderByDesc(ExamAssignment::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExamAssignment assignment : assignments) {
            Exam exam = examMapper.selectById(assignment.getExamId());
            if (exam == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("examId", exam.getId());
            item.put("name", exam.getName() == null || exam.getName().isBlank()
                    ? "练习 #" + exam.getId() : exam.getName());
            item.put("source", exam.getSource());
            item.put("subject", exam.getSubject());
            item.put("description", exam.getDescription());
            item.put("status", assignment.getStatus());
            item.put("totalScore", assignment.getTotalScore());
            item.put("fullScore", assignment.getFullScore());
            item.put("pendingCount", assignment.getPendingCount());
            item.put("questionCount", examQuestions(exam.getId()).size());
            item.put("assignedAt", assignment.getCreatedAt());
            item.put("submittedAt", assignment.getSubmittedAt());
            item.put("gradedAt", assignment.getGradedAt());
            item.put("teacherName", displayNameOf(exam.getCreatedBy()));
            result.add(item);
        }
        return result;
    }

    /**
     * 学生取题作答（或复盘）。
     * <b>未定稿前绝不返回 answer / analysis</b>，否则等于把答案发给学生。
     */
    public Map<String, Object> studentPaperDetail(Long examId, Long studentId) {
        ExamAssignment assignment = requireAssignment(examId, studentId);
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BizException("卷子不存在: " + examId);
        }
        boolean revealed = ExamAssignment.ST_GRADED.equals(assignment.getStatus());

        Map<Long, AnswerRecord> mine = new HashMap<>();
        answerRecordMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getAssignmentId, assignment.getId()))
                .forEach(r -> mine.put(r.getQuestionId(), r));

        List<Map<String, Object>> questions = new ArrayList<>();
        for (ExamQuestion eq : examQuestions(examId)) {
            Map<String, Object> view = questionView(eq, revealed);
            AnswerRecord record = mine.get(eq.getQuestionId());
            if (record != null) {
                view.put("studentAnswer", record.getStudentAnswer());
                view.put("score", record.getScore());
                view.put("comment", record.getComment());
                view.put("gradedBy", record.getGradedBy());
            }
            questions.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", exam.getId());
        result.put("name", exam.getName());
        result.put("source", exam.getSource());
        result.put("subject", exam.getSubject());
        result.put("description", exam.getDescription());
        result.put("teacherName", displayNameOf(exam.getCreatedBy()));
        result.put("status", assignment.getStatus());
        result.put("totalScore", assignment.getTotalScore());
        result.put("fullScore", assignment.getFullScore());
        result.put("pendingCount", assignment.getPendingCount());
        result.put("submittedAt", assignment.getSubmittedAt());
        result.put("gradedAt", assignment.getGradedAt());
        result.put("answerRevealed", revealed);
        result.put("questions", questions);
        return result;
    }

    // ==================== 提交与自动批改 ====================

    /** 学生交卷：机器逐题判分，客观题直接定分，主观题给参考分并挂起待教师复核 */
    @Transactional
    public Map<String, Object> submit(Long examId, Long studentId, AssignmentSubmitReq req) {
        ExamAssignment assignment = requireAssignment(examId, studentId);
        if (!ExamAssignment.ST_ASSIGNED.equals(assignment.getStatus())) {
            throw new BizException("这份作业已提交，不能重复提交");
        }
        Map<Long, String> answerMap = new HashMap<>();
        for (AssignmentSubmitReq.Item item : req.items()) {
            answerMap.put(item.questionId(), item.answer());
        }

        List<ExamQuestion> examQuestions = examQuestions(examId);
        if (examQuestions.isEmpty()) {
            throw new BizException("卷子里没有题目");
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal full = BigDecimal.ZERO;
        int pending = 0;
        Map<Long, List<Double>> rateByNode = new HashMap<>();
        Map<Long, Integer> countByNode = new HashMap<>();

        for (ExamQuestion eq : examQuestions) {
            Question question = questionMapper.selectById(eq.getQuestionId());
            if (question == null) {
                continue;
            }
            String given = answerMap.get(eq.getQuestionId());
            AutoGrader.Result graded = AutoGrader.grade(
                    question.getQuestionType(), question.getAnswer(), question.getScore(), given);

            AnswerRecord record = new AnswerRecord();
            record.setStudentId(studentId);
            record.setExamId(examId);
            record.setAssignmentId(assignment.getId());
            record.setQuestionId(question.getId());
            record.setNodeId(eq.getNodeId());
            record.setStudentAnswer(given);
            record.setAutoScore(graded.score());
            record.setScore(graded.score());
            record.setFullScore(nz(question.getScore()));
            record.setGradedBy(AnswerRecord.BY_AUTO);
            record.setComment(graded.note() == null ? "" : graded.note());
            answerRecordMapper.insert(record);

            total = total.add(graded.score());
            full = full.add(nz(question.getScore()));
            if (graded.needsReview()) {
                pending++;
            }
            if (eq.getNodeId() != null && eq.getNodeId() > 0 && nz(question.getScore()).signum() > 0) {
                double rate = graded.score().doubleValue() / question.getScore().doubleValue();
                rateByNode.computeIfAbsent(eq.getNodeId(), k -> new ArrayList<>()).add(rate);
                countByNode.merge(eq.getNodeId(), 1, Integer::sum);
            }
        }

        assignment.setTotalScore(total.setScale(2, RoundingMode.HALF_UP));
        assignment.setFullScore(full.setScale(2, RoundingMode.HALF_UP));
        assignment.setPendingCount(pending);
        assignment.setSubmittedAt(LocalDateTime.now());
        if (pending == 0) {
            assignment.setStatus(ExamAssignment.ST_GRADED);
            assignment.setGradedAt(LocalDateTime.now());
            assignment.setGraderId(null);
        } else {
            assignment.setStatus(ExamAssignment.ST_SUBMITTED);
        }
        assignmentMapper.updateById(assignment);

        // 诊断卷走「覆盖式初始化掌握度」，普通练习走 EMA 增量
        Exam submittedExam = examMapper.selectById(examId);
        Map<Long, Double> rateAvg = averageByNode(rateByNode);
        boolean diagnosis = submittedExam != null && Exam.SRC_DIAGNOSIS.equals(submittedExam.getSource());
        Map<Long, MasteryService.MasteryChange> changes = diagnosis
                ? masteryService.initializeMastery(studentId, rateAvg)
                : masteryService.updateMastery(studentId, rateAvg);

        // 学习记录：按知识点逐条落库，带掌握度前后快照。
        // 这是「推荐 → 执行 → 掌握度变化」闭环的最后一段，也是推荐引擎近期重复惩罚的数据来源。
        learningRecordService.recordPractice(studentId,
                diagnosis ? com.kgquiz.quiz.entity.LearningRecord.A_DIAGNOSIS
                        : com.kgquiz.quiz.entity.LearningRecord.A_PRACTICE,
                examId, examNameOf(submittedExam), rateAvg, changes, countByNode);

        // 交卷即收掉对应的学习任务（诊断任务 / 从任务点进来的专项练习）
        int closed = learningTaskService.markTasksDone(studentId, examId,
                submittedExam == null ? null : submittedExam.getSource());
        if (closed > 0) {
            log.info("学生 #{} 交卷 #{}，已关闭 {} 条待办学习任务", studentId, examId, closed);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("studentId", studentId);
        result.put("source", submittedExam == null ? null : submittedExam.getSource());
        result.put("diagnosis", diagnosis);
        result.put("status", assignment.getStatus());
        result.put("totalScore", assignment.getTotalScore());
        result.put("fullScore", assignment.getFullScore());
        result.put("pendingCount", pending);
        result.put("autoGraded", true);
        return result;
    }

    // ==================== 教师批改 ====================

    /** 待批改/已批改答卷列表（教师视角，含每题学生答案与机器判分） */
    public List<Map<String, Object>> submissions(Long examId, CurrentUser operator) {
        requireOwnedPaper(examId, operator);
        List<ExamAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, examId)
                        .orderByAsc(ExamAssignment::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExamAssignment assignment : assignments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("studentId", assignment.getStudentId());
            item.put("studentName", studentNameOf(assignment.getStudentId()));
            item.put("status", assignment.getStatus());
            item.put("totalScore", assignment.getTotalScore());
            item.put("fullScore", assignment.getFullScore());
            item.put("pendingCount", assignment.getPendingCount());
            item.put("submittedAt", assignment.getSubmittedAt());
            item.put("gradedAt", assignment.getGradedAt());
            result.add(item);
        }
        return result;
    }

    /** 单个学生的答卷明细（批改台用） */
    public Map<String, Object> submissionDetail(Long examId, Long studentId, CurrentUser operator) {
        Exam exam = requireOwnedPaper(examId, operator);
        ExamAssignment assignment = requireAssignment(examId, studentId);
        Map<Long, AnswerRecord> records = new HashMap<>();
        answerRecordMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getAssignmentId, assignment.getId()))
                .forEach(r -> records.put(r.getQuestionId(), r));

        List<Map<String, Object>> questions = new ArrayList<>();
        for (ExamQuestion eq : examQuestions(examId)) {
            Question question = questionMapper.selectById(eq.getQuestionId());
            if (question == null) {
                continue;
            }
            AnswerRecord record = records.get(eq.getQuestionId());
            GraphNode node = eq.getNodeId() != null && eq.getNodeId() > 0
                    ? graphStore.findNode(eq.getNodeId()) : null;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("questionId", question.getId());
            view.put("questionType", question.getQuestionType());
            view.put("stem", question.getStem());
            view.put("options", parseOptions(question.getOptions()));
            view.put("referenceAnswer", question.getAnswer());
            view.put("analysis", question.getAnalysis());
            view.put("fullScore", nz(question.getScore()));
            view.put("nodeName", node == null ? "" : node.getName());
            view.put("studentAnswer", record == null ? null : record.getStudentAnswer());
            view.put("autoScore", record == null ? null : record.getAutoScore());
            view.put("score", record == null ? null : record.getScore());
            view.put("comment", record == null ? "" : record.getComment());
            view.put("gradedBy", record == null ? null : record.getGradedBy());
            view.put("needsReview", record != null && !AnswerRecord.BY_MANUAL.equals(record.getGradedBy())
                    && isSubjective(question.getQuestionType()));
            questions.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("name", exam.getName());
        result.put("studentId", studentId);
        result.put("studentName", studentNameOf(studentId));
        result.put("status", assignment.getStatus());
        result.put("totalScore", assignment.getTotalScore());
        result.put("fullScore", assignment.getFullScore());
        result.put("pendingCount", assignment.getPendingCount());
        result.put("submittedAt", assignment.getSubmittedAt());
        result.put("questions", questions);
        return result;
    }

    /** 教师改分并定稿；掌握度按得分率变化量修正 */
    @Transactional
    public Map<String, Object> grade(Long examId, Long studentId, GradeReq req, CurrentUser operator) {
        requireOwnedPaper(examId, operator);
        ExamAssignment assignment = requireAssignment(examId, studentId);

        Map<Long, AnswerRecord> records = new HashMap<>();
        answerRecordMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getAssignmentId, assignment.getId()))
                .forEach(r -> records.put(r.getQuestionId(), r));
        if (records.isEmpty()) {
            throw new BizException("该学生还没有提交答卷");
        }

        // 改分前的得分率快照
        Map<Long, List<Double>> oldRates = rateSnapshot(records.values());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal full = BigDecimal.ZERO;
        int changed = 0;

        for (GradeReq.Item item : req.items()) {
            AnswerRecord record = records.get(item.questionId());
            if (record == null) {
                throw new BizException("题目 " + item.questionId() + " 不在该学生的答卷里");
            }
            BigDecimal fullScore = nz(record.getFullScore());
            BigDecimal score = item.score();
            if (score == null || score.signum() < 0) {
                throw new BizException("题目分数不能为负或为空");
            }
            if (score.compareTo(fullScore) > 0) {
                throw new BizException("第 " + item.questionId() + " 题分数超过满分 " + fullScore);
            }
            record.setScore(score.setScale(2, RoundingMode.HALF_UP));
            record.setGradedBy(AnswerRecord.BY_MANUAL);
            record.setComment(item.comment() == null ? "" : item.comment().trim());
            answerRecordMapper.updateById(record);
            changed++;
        }

        // 重算总分（未在 req 里出现的题保持原分）
        for (AnswerRecord record : records.values()) {
            total = total.add(nz(record.getScore()));
            full = full.add(nz(record.getFullScore()));
        }
        assignment.setTotalScore(total.setScale(2, RoundingMode.HALF_UP));
        assignment.setFullScore(full.setScale(2, RoundingMode.HALF_UP));
        for (AnswerRecord record : records.values()) {
            Question question = questionMapper.selectById(record.getQuestionId());
            if (question != null && isSubjective(question.getQuestionType())
                    && !AnswerRecord.BY_MANUAL.equals(record.getGradedBy())) {
                throw new BizException("请复核全部主观题后再定稿");
            }
        }
        assignment.setPendingCount(0);
        assignment.setStatus(ExamAssignment.ST_GRADED);
        assignment.setGradedAt(LocalDateTime.now());
        assignment.setGraderId(operator.userId());
        assignmentMapper.updateById(assignment);

        Map<Long, List<Double>> newRates = rateSnapshot(records.values());
        Map<Long, Double> delta = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Double>> entry : newRates.entrySet()) {
            double oldAvg = average(oldRates.get(entry.getKey()));
            double newAvg = average(entry.getValue());
            if (Math.abs(newAvg - oldAvg) > 1e-9) {
                delta.put(entry.getKey(), newAvg - oldAvg);
            }
        }
        Exam gradedExam = examMapper.selectById(examId);
        Map<Long, MasteryService.MasteryChange> adjustChanges = masteryService.correctExam(studentId, examId,
                gradedExam != null && Exam.SRC_DIAGNOSIS.equals(gradedExam.getSource()), delta, averageByNode(newRates));
        // 教师改分也要进学习记录：否则学生端时间线只剩机器判分，看不到老师纠正过什么
        for (Map.Entry<Long, MasteryService.MasteryChange> e : adjustChanges.entrySet()) {
            learningRecordService.recordAdjust(studentId, e.getKey(), examId,
                    average(newRates.get(e.getKey())), e.getValue(),
                    "教师批改后修正：" + examNameOf(examMapper.selectById(examId)));
        }

        log.info("教师 {} 批改卷子 #{} 学生 #{}：{} 题，{} -> {} 分", operator.username(),
                examId, studentId, changed, assignment.getTotalScore(), assignment.getFullScore());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("studentId", studentId);
        result.put("status", assignment.getStatus());
        result.put("totalScore", assignment.getTotalScore());
        result.put("fullScore", assignment.getFullScore());
        result.put("gradedQuestions", changed);
        result.put("masteryDelta", delta);
        return result;
    }

    /** 退回重做：清掉答卷，恢复为待作答 */
    @Transactional
    public Map<String, Object> reset(Long examId, Long studentId, CurrentUser operator) {
        requireOwnedPaper(examId, operator);
        ExamAssignment assignment = requireAssignment(examId, studentId);
        answerRecordMapper.delete(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getAssignmentId, assignment.getId()));
        assignment.setStatus(ExamAssignment.ST_ASSIGNED);
        assignment.setTotalScore(null);
        assignment.setFullScore(null);
        assignment.setPendingCount(0);
        assignment.setSubmittedAt(null);
        assignment.setGradedAt(null);
        assignment.setGraderId(null);
        assignmentMapper.updateById(assignment);
        return Map.of("examId", examId, "studentId", studentId, "status", assignment.getStatus());
    }

    // ==================== 管理员视角：教师工作量 ====================

    /** 每个教师出了多少卷、发给多少学生、还有多少待批改 */
    public List<Map<String, Object>> teacherWorkload() {
        LambdaQueryWrapper<SysUser> tw = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, "TEACHER")
                .orderByAsc(SysUser::getId);
        Long scope = UserContext.scopeOrgId();
        if (scope != null) {
            tw.eq(SysUser::getOrgId, scope);
        }
        List<SysUser> teachers = sysUserMapper.selectList(tw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser teacher : teachers) {
            List<Exam> papers = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                    .eq(Exam::getSource, Exam.SRC_TEACHER)
                    .eq(Exam::getCreatedBy, teacher.getId()));
            long assigned = 0;
            long pending = 0;
            long graded = 0;
            for (Exam paper : papers) {
                assigned += assignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, paper.getId()));
                pending += assignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, paper.getId())
                        .eq(ExamAssignment::getStatus, ExamAssignment.ST_SUBMITTED));
                graded += assignmentMapper.selectCount(new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, paper.getId())
                        .eq(ExamAssignment::getStatus, ExamAssignment.ST_GRADED));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("teacherId", teacher.getId());
            item.put("username", teacher.getUsername());
            item.put("displayName", teacher.getDisplayName());
            item.put("enabled", teacher.getEnabled());
            item.put("lastLoginAt", teacher.getLastLoginAt());
            item.put("paperCount", papers.size());
            item.put("assignedCount", assigned);
            item.put("pendingCount", pending);
            item.put("gradedCount", graded);
            result.add(item);
        }
        return result;
    }

    // ==================== 内部工具 ====================

    private Exam requireOwnedPaper(Long examId, CurrentUser operator) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BizException("卷子不存在: " + examId);
        }
        if (operator.isAdmin()) {
            return exam;
        }
        // 机构边界优先：跨机构的卷子一律当作不存在，避免探测
        if (exam.getOrgId() != null && !exam.getOrgId().equals(operator.orgId())) {
            throw new BizException("卷子不存在: " + examId);
        }
        if (exam.getCreatedBy() == null || !exam.getCreatedBy().equals(operator.userId())) {
            throw new ForbiddenException("只能管理自己创建的卷子");
        }
        return exam;
    }

    private ExamAssignment requireAssignment(Long examId, Long studentId) {
        studentAccessService.requireStudent(studentId);
        ExamAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<ExamAssignment>()
                .eq(ExamAssignment::getExamId, examId)
                .eq(ExamAssignment::getStudentId, studentId)
                .last("limit 1"));
        if (assignment == null) {
            throw new ForbiddenException("这份作业没有发给你");
        }
        return assignment;
    }

    private List<ExamQuestion> examQuestions(Long examId) {
        return examQuestionMapper.selectList(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId)
                .orderByAsc(ExamQuestion::getSortOrder));
    }

    /** 题目在卷子里归属的知识点：取题目关联的第一个知识点，没有则 0（不参与掌握度更新） */
    private Long firstNodeOf(Long questionId) {
        QuestionNode link = questionNodeMapper.selectOne(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getQuestionId, questionId).last("limit 1"));
        return link == null || link.getNodeId() == null ? 0L : link.getNodeId();
    }

    private Map<String, Object> questionView(ExamQuestion eq, boolean includeAnswers) {
        Question question = questionMapper.selectById(eq.getQuestionId());
        Map<String, Object> view = new LinkedHashMap<>();
        if (question == null) {
            view.put("questionId", eq.getQuestionId());
            view.put("stem", "（题目已删除）");
            return view;
        }
        GraphNode node = eq.getNodeId() != null && eq.getNodeId() > 0
                ? graphStore.findNode(eq.getNodeId()) : null;
        view.put("questionId", question.getId());
        view.put("questionType", question.getQuestionType());
        view.put("stem", question.getStem());
        view.put("options", parseOptions(question.getOptions()));
        // 题目满分用 fullScore；学生得分统一用 score，两者不能重名互相覆盖
        view.put("fullScore", nz(question.getScore()));
        view.put("difficulty", question.getDifficulty());
        view.put("nodeId", eq.getNodeId());
        view.put("nodeName", node == null ? "" : node.getName());
        if (includeAnswers) {
            view.put("answer", question.getAnswer());
            view.put("analysis", question.getAnalysis());
        }
        return view;
    }

    private Map<String, Object> paperSummary(Exam exam) {
        List<ExamQuestion> questions = examQuestions(exam.getId());
        BigDecimal totalScore = questions.stream()
                .map(eq -> {
                    Question q = questionMapper.selectById(eq.getQuestionId());
                    return q == null ? BigDecimal.ZERO : nz(q.getScore());
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ExamAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<ExamAssignment>().eq(ExamAssignment::getExamId, exam.getId()));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("examId", exam.getId());
        item.put("name", exam.getName());
        item.put("source", exam.getSource());
        item.put("subject", exam.getSubject());
        item.put("textbook", exam.getTextbook());
        item.put("description", exam.getDescription());
        item.put("status", exam.getStatus());
        item.put("createdBy", exam.getCreatedBy());
        item.put("creatorName", displayNameOf(exam.getCreatedBy()));
        item.put("createdAt", exam.getCreatedAt());
        item.put("questionCount", questions.size());
        item.put("totalScore", totalScore);
        item.put("assignedCount", assignments.size());
        item.put("submittedCount", assignments.stream()
                .filter(a -> !ExamAssignment.ST_ASSIGNED.equals(a.getStatus())).count());
        item.put("gradedCount", assignments.stream()
                .filter(a -> ExamAssignment.ST_GRADED.equals(a.getStatus())).count());
        return item;
    }

    private List<Map<String, Object>> assignmentViews(Long examId) {
        List<ExamAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<ExamAssignment>()
                        .eq(ExamAssignment::getExamId, examId)
                        .orderByAsc(ExamAssignment::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExamAssignment assignment : assignments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("studentId", assignment.getStudentId());
            item.put("studentName", studentNameOf(assignment.getStudentId()));
            item.put("status", assignment.getStatus());
            item.put("totalScore", assignment.getTotalScore());
            item.put("fullScore", assignment.getFullScore());
            item.put("pendingCount", assignment.getPendingCount());
            item.put("assignedAt", assignment.getCreatedAt());
            item.put("submittedAt", assignment.getSubmittedAt());
            item.put("gradedAt", assignment.getGradedAt());
            result.add(item);
        }
        return result;
    }

    private Map<Long, List<Double>> rateSnapshot(java.util.Collection<AnswerRecord> records) {
        Map<Long, List<Double>> rates = new HashMap<>();
        for (AnswerRecord record : records) {
            if (record.getNodeId() == null || record.getNodeId() <= 0) {
                continue;
            }
            double fullScore = nz(record.getFullScore()).doubleValue();
            if (fullScore <= 0) {
                continue;
            }
            rates.computeIfAbsent(record.getNodeId(), k -> new ArrayList<>())
                    .add(nz(record.getScore()).doubleValue() / fullScore);
        }
        return rates;
    }

    private Map<Long, Double> averageByNode(Map<Long, List<Double>> rates) {
        Map<Long, Double> avg = new LinkedHashMap<>();
        rates.forEach((nodeId, list) -> avg.put(nodeId, average(list)));
        return avg;
    }

    /** 卷子显示名；记录流水要留一句人能读懂的话，纯 id 没有意义 */
    private static String examNameOf(Exam exam) {
        if (exam == null) {
            return "未知卷子";
        }
        if (exam.getName() != null && !exam.getName().isBlank()) {
            return exam.getName();
        }
        return Exam.SRC_DIAGNOSIS.equals(exam.getSource()) ? "诊断测评" : "练习 #" + exam.getId();
    }

    private double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private boolean isSubjective(String questionType) {
        return "SHORT_ANSWER".equals(questionType) || "FILL_BLANK".equals(questionType);
    }

    private String studentNameOf(Long studentId) {
        if (studentId == null) {
            return "";
        }
        Student student = studentMapper.selectById(studentId);
        return student == null ? "学生 #" + studentId : student.getName();
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return "自适应引擎";
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? "已删除账号 #" + userId : user.getDisplayName();
    }

    private BigDecimal sumScore(List<Map<String, Object>> questions) {
        return questions.stream()
                .map(q -> (BigDecimal) q.getOrDefault("fullScore", BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
            return "{}";
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value.trim();
    }
}
