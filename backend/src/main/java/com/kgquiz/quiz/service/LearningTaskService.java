package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.Exam;
import com.kgquiz.quiz.entity.LearningTask;
import com.kgquiz.quiz.entity.NodeContent;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.LearningTaskMapper;
import com.kgquiz.quiz.mapper.NodeContentMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习任务流：把「推荐结果」落成学生每天打开就能走的清单。
 *
 * <p>任务由三段构成，对应「诊 → 学 → 练」：</p>
 * <ol>
 *   <li>没有诊断记录 → 先给一条「入学诊断」任务，确立学习起点；</li>
 *   <li>已诊断 → 按推荐规则给出若干「学」（看知识点内容）+「练」（做这个知识点的题）；</li>
 *   <li>已掌握的知识点转为「复习」，用低强度回顾防止遗忘。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningTaskService {

    private final LearningTaskMapper learningTaskMapper;
    private final RecommendService recommendService;
    private final DiagnosisService diagnosisService;
    private final ExamService examService;
    private final NodeContentMapper nodeContentMapper;
    private final StudentMapper studentMapper;
    private final StudentGraphMapper studentGraphMapper;
    private final StudentNodeStateMapper studentNodeStateMapper;
    private final GraphStore graphStore;

    // ==================== 今日学习 ====================

    /**
     * 今日学习清单。若当前没有任何未完成任务，则按推荐规则自动生成一批
     * （「日复一日走下去」的关键：学生第二天打开就有新任务）。
     */
    @Transactional
    public Map<String, Object> today(Long studentId) {
        Student student = requireStudent(studentId);
        long pending = countPending(studentId);

        boolean generated = false;
        String notice = "";
        if (pending == 0) {
            Map<String, Object> gen = generate(studentId, true);
            generated = true;
            notice = String.valueOf(gen.get("notice"));
        }
        return snapshot(student, generated, notice);
    }

    /** 只读清单：教师查看某个学生今天的学习安排（不会自动生成新任务） */
    public Map<String, Object> overview(Long studentId) {
        Student student = requireStudent(studentId);
        return snapshot(student, false, "");
    }

    private long countPending(Long studentId) {
        Long c = learningTaskMapper.selectCount(new LambdaQueryWrapper<LearningTask>()
                .eq(LearningTask::getStudentId, studentId)
                .eq(LearningTask::getStatus, LearningTask.S_PENDING));
        return c == null ? 0 : c;
    }

    /** 把任务清单 + 进度拼成统一视图（today 与 overview 共用） */
    private Map<String, Object> snapshot(Student student, boolean generated, String notice) {
        Long studentId = student.getId();
        List<LearningTask> all = tasksOf(studentId);
        long pending = all.stream().filter(t -> LearningTask.S_PENDING.equals(t.getStatus())).count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (LearningTask t : all) {
            items.add(toView(t, student));
        }
        long doneToday = all.stream()
                .filter(t -> LearningTask.S_DONE.equals(t.getStatus())
                        && t.getCompletedAt() != null
                        && t.getCompletedAt().toLocalDate().equals(LocalDate.now()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("studentName", student.getName());
        result.put("generated", generated);
        result.put("notice", notice);
        result.put("hasDiagnosis", hasDiagnosisExam(studentId));
        result.put("diagnosisCompleted", diagnosisService.completed(studentId));
        result.put("pendingCount", pending);
        result.put("doneTodayCount", doneToday);
        result.put("tasks", items);
        result.put("progress", progressOf(studentId));
        return result;
    }

    private List<LearningTask> tasksOf(Long studentId) {
        return learningTaskMapper.selectList(new LambdaQueryWrapper<LearningTask>()
                .eq(LearningTask::getStudentId, studentId)
                .orderByAsc(LearningTask::getStatus)
                .orderByAsc(LearningTask::getPriority)
                .orderByAsc(LearningTask::getId));
    }

    /** 掌握度总览，给学生端「我的学习」做进度条 */
    public Map<String, Object> progressOf(Long studentId) {
        StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                .eq(StudentGraph::getStudentId, studentId)
                .orderByDesc(StudentGraph::getId)
                .last("limit 1"));
        Map<String, Object> m = new LinkedHashMap<>();
        if (sg == null) {
            m.put("total", 0);
            m.put("mastered", 0);
            m.put("weak", 0);
            m.put("avgMastery", 0);
            return m;
        }
        List<StudentNodeState> states = studentNodeStateMapper.selectList(
                new LambdaQueryWrapper<StudentNodeState>().eq(StudentNodeState::getStudentGraphId, sg.getId()));
        int total = graphStore.listNodes(sg.getSourceGraphId()).size();
        long mastered = states.stream()
                .filter(s -> s.getMastery() != null && s.getMastery().doubleValue() >= 0.7).count();
        long weak = states.stream()
                .filter(s -> s.getMastery() != null && s.getMastery().doubleValue() < 0.4).count();
        double avg = states.stream()
                .mapToDouble(s -> s.getMastery() == null ? 0 : s.getMastery().doubleValue())
                .average().orElse(0);
        m.put("total", total);
        m.put("mastered", mastered);
        m.put("weak", weak);
        m.put("avgMastery", Math.round(avg * 10000) / 10000.0);
        return m;
    }

    private boolean hasDiagnosis(Long studentId) {
        Long c = learningTaskMapper.selectCount(new LambdaQueryWrapper<LearningTask>()
                .eq(LearningTask::getStudentId, studentId)
                .eq(LearningTask::getType, LearningTask.T_DIAGNOSIS));
        return c != null && c > 0;
    }

    /**
     * 是否已经有诊断卷（含未完成）。与 {@link #hasDiagnosis} 的区别：
     * 前者看「诊断任务是否已建过」（用于防止重复建任务），这个看「诊断卷是否存在」
     * （用于前端展示「已诊断 / 待诊断」）。
     */
    private boolean hasDiagnosisExam(Long studentId) {
        return diagnosisService.latestDiagnosis(studentId) != null;
    }

    /**
     * 学生交卷后收掉与之关联的待办任务。
     *
     * <p>两条命中规则：</p>
     * <ul>
     *   <li>诊断卷交卷 → 收掉所有待完成的「入学诊断」任务（诊断任务是先于卷子建立的，没有 examId）；</li>
     *   <li>其他卷子 → 收掉 examId 指向该卷的任务（学生从任务点进练习，开始时会回填 examId）。</li>
     * </ul>
     *
     * @return 被收掉的任务数
     */
    @Transactional
    public int markTasksDone(Long studentId, Long examId, String examSource) {
        boolean diagnosis = Exam.SRC_DIAGNOSIS.equals(examSource);
        List<LearningTask> pending = learningTaskMapper.selectList(new LambdaQueryWrapper<LearningTask>()
                .eq(LearningTask::getStudentId, studentId)
                .eq(LearningTask::getStatus, LearningTask.S_PENDING));
        int closed = 0;
        for (LearningTask task : pending) {
            boolean hit = (diagnosis && LearningTask.T_DIAGNOSIS.equals(task.getType()))
                    || (examId != null && examId.equals(task.getExamId()));
            if (!hit) {
                continue;
            }
            task.setStatus(LearningTask.S_DONE);
            task.setCompletedAt(LocalDateTime.now());
            if (task.getExamId() == null) {
                task.setExamId(examId);
            }
            learningTaskMapper.updateById(task);
            closed++;
        }
        return closed;
    }

    /**
     * 学生点「开始」：把任务换成可执行的东西。
     *
     * <ul>
     *   <li>学 → 返回该知识点已发布的内容 id（没有内容也能看知识点说明）；</li>
     *   <li>诊断 → 已有未交的诊断卷就复用，否则现生成一份；</li>
     *   <li>练 / 复习 → 现出一份针对该知识点的专项练习，并把 examId 回填到任务上。</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> start(Long taskId, Long studentId) {
        LearningTask task = get(taskId);
        checkTaskOwner(task, studentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("type", task.getType());
        out.put("nodeId", task.getNodeId());
        if (task.getNodeId() != null) {
            var node = graphStore.findNode(task.getNodeId());
            out.put("nodeName", node == null ? null : node.getName());
        }

        switch (task.getType()) {
            case LearningTask.T_LEARN -> {
                NodeContent content = task.getContentId() == null
                        ? null : nodeContentMapper.selectById(task.getContentId());
                if (content == null) {
                    content = firstPublishedContent(task.getNodeId());
                }
                out.put("contentId", content == null ? null : content.getId());
                out.put("hasContent", content != null);
            }
            case LearningTask.T_DIAGNOSIS -> {
                Exam exam = diagnosisService.latestDiagnosis(task.getStudentId());
                if (exam == null || diagnosisService.completed(task.getStudentId())) {
                    Map<String, Object> gen = diagnosisService.generate(task.getStudentId(), 1, 20);
                    out.put("examId", gen.get("examId"));
                    out.put("created", true);
                } else {
                    out.put("examId", exam.getId());
                    out.put("created", false);
                }
            }
            default -> {
                Long examId = task.getExamId();
                if (examId == null) {
                    Map<String, Object> exam = examService.generatePracticeForStudent(
                            task.getStudentId(), task.getNodeId(), 5);
                    examId = ((Number) exam.get("examId")).longValue();
                    task.setExamId(examId);
                    learningTaskMapper.updateById(task);
                }
                out.put("examId", examId);
            }
        }
        return out;
    }

    /**
     * 老师给学生布置一条学习任务（不受系统重排影响）。
     */
    @Transactional
    public Map<String, Object> assignByTeacher(Long studentId, Long nodeId, String type, String title,
                                               Long examId, LocalDateTime dueAt) {
        return createTeacherTask(studentId, nodeId, type, title, examId, dueAt);
    }

    // ==================== 任务生成 ====================

    /** 重新生成学习任务（清掉未完成的旧任务，按最新掌握度重排） */
    @Transactional
    public Map<String, Object> regenerate(Long studentId) {
        return generate(studentId, true);
    }

    /**
     * @param clearPending 是否清掉未完成的旧任务（教师手工布置的任务不会被清）
     */
    @Transactional
    public Map<String, Object> generate(Long studentId, boolean clearPending) {
        Student student = requireStudent(studentId);
        if (clearPending) {
            learningTaskMapper.delete(new LambdaQueryWrapper<LearningTask>()
                    .eq(LearningTask::getStudentId, studentId)
                    .eq(LearningTask::getStatus, LearningTask.S_PENDING)
                    .eq(LearningTask::getSource, LearningTask.SRC_SYSTEM));
        }

        // 还没做过诊断 → 首条任务就是诊断
        boolean diagnosed = hasDiagnosis(studentId);
        if (!diagnosed) {
            LearningTask task = new LearningTask();
            task.setOrgId(student.getOrgId());
            task.setStudentId(studentId);
            task.setType(LearningTask.T_DIAGNOSIS);
            task.setTitle("入学诊断：先摸清你的起点");
            task.setReason("还没有诊断记录，先做一次覆盖全部知识点的诊断，系统才能知道该从哪里带你学");
            task.setPriority(0);
            task.setSource(LearningTask.SRC_SYSTEM);
            task.setStatus(LearningTask.S_PENDING);
            learningTaskMapper.insert(task);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created", 1);
            r.put("notice", "尚无诊断记录，已生成「入学诊断」任务");
            r.put("tasks", List.of(toView(task, student)));
            return r;
        }

        List<RecommendService.Recommendation> recs = recommendService.recommend(studentId, null);
        List<LearningTask> created = new ArrayList<>();
        int priority = 1;
        for (RecommendService.Recommendation rec : recs) {
            NodeContent content = firstPublishedContent(rec.nodeId());
            // 学：先看内容。内容缺失也建任务，学生至少能看到知识点说明
            LearningTask learn = new LearningTask();
            learn.setOrgId(student.getOrgId());
            learn.setStudentId(studentId);
            learn.setNodeId(rec.nodeId());
            learn.setType(LearningTask.T_LEARN);
            learn.setContentId(content == null ? null : content.getId());
            learn.setTitle("学习「" + rec.nodeName() + "」");
            learn.setReason(joinReasons(rec));
            learn.setPriority(priority++);
            learn.setSource(LearningTask.SRC_SYSTEM);
            learn.setStatus(LearningTask.S_PENDING);
            learningTaskMapper.insert(learn);
            created.add(learn);

            // 练：同一个知识点紧跟着练一手（无论掌握与否，练都能推进 EMA）
            LearningTask practice = new LearningTask();
            practice.setOrgId(student.getOrgId());
            practice.setStudentId(studentId);
            practice.setNodeId(rec.nodeId());
            practice.setType(LearningTask.T_PRACTICE);
            practice.setTitle("练习「" + rec.nodeName() + "」");
            practice.setReason("学完立刻练，把这个知识点固化下来");
            practice.setPriority(priority++);
            practice.setSource(LearningTask.SRC_SYSTEM);
            practice.setStatus(LearningTask.S_PENDING);
            learningTaskMapper.insert(practice);
            created.add(practice);

            // 已掌握的顺带安排一次复习（低强度）
            if ("SOLID".equals(rec.tier())) {
                LearningTask review = new LearningTask();
                review.setOrgId(student.getOrgId());
                review.setStudentId(studentId);
                review.setNodeId(rec.nodeId());
                review.setType(LearningTask.T_REVIEW);
                review.setTitle("复习「" + rec.nodeName() + "」");
                review.setReason("已掌握，隔一段时间回顾一次防止遗忘");
                review.setPriority(priority++);
                review.setSource(LearningTask.SRC_SYSTEM);
                review.setStatus(LearningTask.S_PENDING);
                learningTaskMapper.insert(review);
                created.add(review);
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("created", created.size());
        r.put("notice", "已按当前掌握度生成 " + created.size() + " 条学习任务");
        r.put("tasks", created.stream().map(t -> toView(t, student)).toList());
        return r;
    }

    private NodeContent firstPublishedContent(Long nodeId) {
        return nodeContentMapper.selectOne(new LambdaQueryWrapper<NodeContent>()
                .eq(NodeContent::getNodeId, nodeId)
                .eq(NodeContent::getStatus, NodeContent.S_PUBLISHED)
                .orderByAsc(NodeContent::getType)
                .orderByDesc(NodeContent::getId)
                .last("limit 1"));
    }

    private String joinReasons(RecommendService.Recommendation rec) {
        if (rec.reasons().isEmpty()) {
            return "系统按掌握度与知识点重要性推荐";
        }
        return String.join("；", rec.reasons());
    }

    // ==================== 任务操作 ====================

    @Transactional
    public Map<String, Object> complete(Long taskId) {
        return complete(taskId, null);
    }

    /**
     * 标记任务完成。
     *
     * @param ownerStudentId 非空时校验任务归属该学生（学生端专用，防止操作他人任务）
     */
    @Transactional
    public Map<String, Object> complete(Long taskId, Long ownerStudentId) {
        LearningTask task = get(taskId);
        checkTaskOwner(task, ownerStudentId);
        task.setStatus(LearningTask.S_DONE);
        task.setCompletedAt(LocalDateTime.now());
        learningTaskMapper.updateById(task);
        Student student = studentMapper.selectById(task.getStudentId());
        return toView(task, student);
    }

    @Transactional
    public Map<String, Object> reopen(Long taskId) {
        return reopen(taskId, null);
    }

    @Transactional
    public Map<String, Object> reopen(Long taskId, Long ownerStudentId) {
        LearningTask task = get(taskId);
        checkTaskOwner(task, ownerStudentId);
        task.setStatus(LearningTask.S_PENDING);
        task.setCompletedAt(null);
        learningTaskMapper.updateById(task);
        return toView(task, studentMapper.selectById(task.getStudentId()));
    }

    private void checkTaskOwner(LearningTask task, Long ownerStudentId) {
        if (ownerStudentId != null && !ownerStudentId.equals(task.getStudentId())) {
            throw new com.kgquiz.quiz.common.ForbiddenException("只能操作自己的学习任务");
        }
    }

    @Transactional
    public Map<String, Object> createTeacherTask(Long studentId, Long nodeId, String type, String title,
                                                 Long examId, LocalDateTime dueAt) {
        Student student = requireStudent(studentId);
        LearningTask task = new LearningTask();
        task.setOrgId(student.getOrgId());
        task.setStudentId(studentId);
        task.setNodeId(nodeId);
        task.setType(type == null || type.isBlank() ? LearningTask.T_LEARN : type.trim().toUpperCase());
        task.setTitle(title == null || title.isBlank() ? "老师布置的学习任务" : title.trim());
        task.setReason("教师布置");
        task.setPriority(0);
        task.setSource(LearningTask.SRC_TEACHER);
        task.setStatus(LearningTask.S_PENDING);
        task.setExamId(examId);
        task.setDueAt(dueAt);
        learningTaskMapper.insert(task);
        return toView(task, student);
    }

    @Transactional
    public void delete(Long taskId) {
        LearningTask task = get(taskId);
        UserContext.checkOrgScope(task.getOrgId());
        learningTaskMapper.deleteById(taskId);
    }

    private LearningTask get(Long taskId) {
        LearningTask task = learningTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("学习任务不存在: " + taskId);
        }
        UserContext.checkOrgScope(task.getOrgId());
        return task;
    }

    private Student requireStudent(Long studentId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException("学生不存在: " + studentId);
        }
        UserContext.checkOrgScope(student.getOrgId());
        return student;
    }

    public Map<String, Object> toView(LearningTask t, Student student) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("studentId", t.getStudentId());
        m.put("studentName", student == null ? null : student.getName());
        m.put("nodeId", t.getNodeId());
        if (t.getNodeId() != null) {
            var node = graphStore.findNode(t.getNodeId());
            m.put("nodeName", node == null ? null : node.getName());
            m.put("nodeDescription", node == null ? null : node.getDescription());
            StudentGraph sg = studentGraphMapper.selectOne(new LambdaQueryWrapper<StudentGraph>()
                    .eq(StudentGraph::getStudentId, t.getStudentId())
                    .orderByDesc(StudentGraph::getId).last("limit 1"));
            if (sg != null) {
                StudentNodeState st = studentNodeStateMapper.selectOne(new LambdaQueryWrapper<StudentNodeState>()
                        .eq(StudentNodeState::getStudentGraphId, sg.getId())
                        .eq(StudentNodeState::getSourceNodeId, t.getNodeId()));
                m.put("mastery", st == null ? 0 : st.getMastery());
            }
        }
        m.put("type", t.getType());
        m.put("title", t.getTitle());
        m.put("reason", t.getReason());
        m.put("priority", t.getPriority());
        m.put("source", t.getSource());
        m.put("status", t.getStatus());
        m.put("examId", t.getExamId());
        m.put("contentId", t.getContentId());
        m.put("dueAt", t.getDueAt());
        m.put("completedAt", t.getCompletedAt());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    /** 学生维度的任务列表（教师查看用，按学生聚合） */
    public Map<Long, List<Map<String, Object>>> mapOf(List<Long> studentIds) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        if (studentIds == null || studentIds.isEmpty()) {
            return out;
        }
        for (LearningTask t : learningTaskMapper.selectList(new LambdaQueryWrapper<LearningTask>()
                .in(LearningTask::getStudentId, studentIds)
                .orderByAsc(LearningTask::getPriority))) {
            out.computeIfAbsent(t.getStudentId(), k -> new ArrayList<>()).add(toView(t, null));
        }
        return out;
    }
}
