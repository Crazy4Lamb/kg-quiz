package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.TeacherTaskReq;
import com.kgquiz.quiz.service.LearningTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 学习任务流：学生端「今天学什么」的清单。
 *
 * <p>任务由系统按推荐规则生成（无诊断 → 先诊断；有诊断 → 学 + 练 + 复习），
 * 教师也可以手工插入任务。学生端打开首页若发现没有未完成任务，会自动补一批，
 * 保证「日复一日走下去」。</p>
 */
@RestController
@RequestMapping("/api/tasks")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class LearningTaskController {

    private final LearningTaskService learningTaskService;

    // ==================== 学生端 ====================

    /** 今日学习清单（首次打开会自动生成） */
    @GetMapping("/my/today")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> today() {
        return R.ok(learningTaskService.today(requireOwnStudentId()));
    }

    /** 按最新掌握度重排我的任务 */
    @PostMapping("/my/regenerate")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> regenerate() {
        return R.ok(learningTaskService.regenerate(requireOwnStudentId()));
    }

    /** 完成任务 */
    @PostMapping("/my/{taskId}/complete")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> complete(@PathVariable Long taskId) {
        return R.ok(learningTaskService.complete(taskId, requireOwnStudentId()));
    }

    /**
     * 开始任务：学 → 返回内容 id；诊断 → 返回诊断卷 id；练 / 复习 → 现出一份专项练习并返回卷 id。
     * 前端据返回的 id 决定跳到内容页还是答题页。
     */
    @PostMapping("/my/{taskId}/start")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> start(@PathVariable Long taskId) {
        return R.ok(learningTaskService.start(taskId, requireOwnStudentId()));
    }

    /** 撤销完成（误点 / 想再做一遍） */
    @PostMapping("/my/{taskId}/reopen")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> reopen(@PathVariable Long taskId) {
        return R.ok(learningTaskService.reopen(taskId, requireOwnStudentId()));
    }

    // ==================== 教师 / 管理员 ====================

    /** 查看某个学生的学习清单（只读） */
    @GetMapping("/student/{studentId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> ofStudent(@PathVariable Long studentId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(learningTaskService.overview(studentId));
    }

    /** 按最新掌握度重排某个学生的任务 */
    @PostMapping("/student/{studentId}/regenerate")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> regenerateFor(@PathVariable Long studentId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(learningTaskService.regenerate(studentId));
    }

    /** 教师给学生布置一条任务 */
    @PostMapping("/teacher")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> assignByTeacher(@RequestBody TeacherTaskReq req) {
        if (req.studentId() == null) {
            throw new BizException("请指定学生");
        }
        UserContext.checkStudentScope(req.studentId());
        return R.ok(learningTaskService.assignByTeacher(req.studentId(), req.nodeId(), req.type(),
                req.title(), req.examId(), parseDueAt(req.dueAt())));
    }

    /** 删除任务（教师 / 管理员） */
    @DeleteMapping("/{taskId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Void> delete(@PathVariable Long taskId) {
        learningTaskService.delete(taskId);
        return R.ok();
    }

    // ==================== 内部 ====================

    private static LocalDateTime parseDueAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BizException("截止时间格式不正确，应形如 2026-10-20T18:00");
        }
    }

    private Long requireOwnStudentId() {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return current.studentId();
    }
}
