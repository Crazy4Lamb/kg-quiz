package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.AssignmentSubmitReq;
import com.kgquiz.quiz.dto.GradeReq;
import com.kgquiz.quiz.dto.PaperAssignReq;
import com.kgquiz.quiz.dto.PaperCreateReq;
import com.kgquiz.quiz.dto.PlanGenerateReq;
import com.kgquiz.quiz.service.AssignmentService;
import com.kgquiz.quiz.service.ExamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出题、组卷、作业与批改。
 *
 * <p>角色边界：
 * <ul>
 *   <li>自适应出题（/plans、/confirm）与组卷批改（/papers/**）只给教师和管理员</li>
 *   <li>学生走 /my/**，且 studentId 一律取自会话，不能传别人的</li>
 *   <li>学生取题时后端不会返回答案与解析，交卷定稿后才放开</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/exams")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;
    private final AssignmentService assignmentService;

    // ==================== 自适应出题（教师 / 管理员）====================

    /** 生成出题方案（PENDING 状态） */
    @PostMapping("/plans")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> generatePlan(@Valid @RequestBody PlanGenerateReq req) throws Exception {
        UserContext.checkStudentScope(req.studentId());
        return R.ok(examService.generatePlan(req));
    }

    /** 确认方案 → 生成练习并自动指派给该学生 */
    @PostMapping("/{examId}/confirm")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> confirm(@PathVariable Long examId) throws Exception {
        return R.ok(examService.confirmPlan(examId));
    }

    // ==================== 组卷（教师 / 管理员）====================

    /** 卷子列表：教师看自己出的，管理员可用 teacherId 过滤或看全部 */
    @GetMapping("/papers")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> papers(@RequestParam(required = false) Long teacherId) {
        return R.ok(assignmentService.listPapers(UserContext.require(), teacherId));
    }

    /** 从题库挑题组卷 */
    @PostMapping("/papers")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> createPaper(@Valid @RequestBody PaperCreateReq req) {
        return R.ok(assignmentService.createPaper(req, UserContext.require()));
    }

    @GetMapping("/papers/{examId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> paperDetail(@PathVariable Long examId) {
        return R.ok(assignmentService.paperDetail(examId, UserContext.require(), true));
    }

    @DeleteMapping("/papers/{examId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> deletePaper(@PathVariable Long examId) {
        return R.ok(assignmentService.deletePaper(examId, UserContext.require()));
    }

    /** 把卷子发给指定学生 */
    @PostMapping("/papers/{examId}/assign")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> assign(@PathVariable Long examId,
                                         @Valid @RequestBody PaperAssignReq req) {
        return R.ok(assignmentService.assign(examId, req, UserContext.require()));
    }

    /** 撤销某个学生的指派（已提交的不能撤） */
    @DeleteMapping("/papers/{examId}/assign/{studentId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> unassign(@PathVariable Long examId, @PathVariable Long studentId) {
        return R.ok(assignmentService.unassign(examId, studentId, UserContext.require()));
    }

    /** 交卷情况一览（谁交了、多少分、还有几题待批改） */
    @GetMapping("/papers/{examId}/submissions")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> submissions(@PathVariable Long examId) {
        return R.ok(assignmentService.submissions(examId, UserContext.require()));
    }

    /** 单个学生的答卷明细（批改台） */
    @GetMapping("/papers/{examId}/submissions/{studentId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> submissionDetail(@PathVariable Long examId,
                                                   @PathVariable Long studentId) {
        return R.ok(assignmentService.submissionDetail(examId, studentId, UserContext.require()));
    }

    /** 教师改分并定稿，掌握度按得分率变化量修正 */
    @PostMapping("/papers/{examId}/submissions/{studentId}/grade")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> grade(@PathVariable Long examId, @PathVariable Long studentId,
                                        @Valid @RequestBody GradeReq req) {
        return R.ok(assignmentService.grade(examId, studentId, req, UserContext.require()));
    }

    /** 退回重做：清空答卷，恢复为待作答 */
    @PostMapping("/papers/{examId}/submissions/{studentId}/reset")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> resetSubmission(@PathVariable Long examId,
                                                  @PathVariable Long studentId) {
        return R.ok(assignmentService.reset(examId, studentId, UserContext.require()));
    }

    /** 管理员视角：各教师出了多少卷、还有多少待批改 */
    @GetMapping("/teacher-workload")
    @RequireRole(UserRole.ADMIN)
    public R<List<Map<String, Object>>> teacherWorkload() {
        return R.ok(assignmentService.teacherWorkload());
    }

    // ==================== 学生：我的作业 ====================

    /** 老师发给我的作业 */
    @GetMapping("/my")
    @RequireRole(UserRole.STUDENT)
    public R<List<Map<String, Object>>> myAssignments() {
        return R.ok(assignmentService.myAssignments(requireOwnStudentId()));
    }

    /** 取题作答或复盘（未定稿不带答案） */
    @GetMapping("/my/{examId}")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> myPaperDetail(@PathVariable Long examId) {
        return R.ok(assignmentService.studentPaperDetail(examId, requireOwnStudentId()));
    }

    /** 交卷：后端自动批改 */
    @PostMapping("/my/{examId}/submit")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> submit(@PathVariable Long examId,
                                         @Valid @RequestBody AssignmentSubmitReq req) {
        return R.ok(assignmentService.submit(examId, requireOwnStudentId(), req));
    }

    /**
     * 自助专项练习：针对某个知识点现出一份小题量的卷子并指派给自己。
     * 学习任务流里「练 / 复习」任务走的就是这里。
     */
    @PostMapping("/my/practice")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> practice(@RequestParam(required = false) Long nodeId,
                                           @RequestParam(required = false) Integer count) {
        return R.ok(examService.generatePracticeForStudent(requireOwnStudentId(), nodeId, count));
    }

    // ==================== 通用查询 ====================

    /** 练习列表：学生只看自己的，教师/管理员可按 studentId 过滤或看全部 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long studentId) {
        Long scoped = UserContext.scopeStudentId(studentId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var exam : examService.listExams(scoped)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("examId", exam.getId());
            item.put("studentId", exam.getStudentId());
            item.put("name", exam.getName());
            item.put("source", exam.getSource());
            item.put("status", exam.getStatus());
            item.put("createdAt", exam.getCreatedAt() == null ? "" : exam.getCreatedAt().toString());
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 练习详情（含答案与解析）——<b>只给教师 / 管理员</b>。
     *
     * <p>学生一律走 {@code /api/exams/my/{examId}}：那条链路在交卷定稿前不会返回答案，
     * 否则等于把答案提前发给学生。</p>
     */
    @GetMapping("/{examId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> detail(@PathVariable Long examId) {
        return R.ok(examService.examDetail(examId));
    }

    /** 学生掌握度总览 */
    @GetMapping("/mastery")
    public R<Map<String, Object>> mastery(@RequestParam(required = false) Long studentId) {
        Long scoped = UserContext.scopeStudentId(studentId);
        if (scoped == null) {
            throw new BizException("请指定 studentId");
        }
        return R.ok(examService.masteryOverview(scoped));
    }

    // ==================== 内部 ====================

    /** 学生自己的 studentId，未绑定档案直接拒绝 */
    private Long requireOwnStudentId() {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return current.studentId();
    }

    /** 学生只能操作属于自己的练习 */
    private void checkExamScope(Long examId) {
        CurrentUser current = UserContext.require();
        if (!current.isStudent()) {
            return;
        }
        Long owner = examService.ownerOf(examId);
        if (owner == null || current.studentId() == null || !current.studentId().equals(owner)) {
            throw new ForbiddenException("只能操作自己的练习");
        }
    }
}
