package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.MasteryAdjustReq;
import com.kgquiz.quiz.dto.StudentCreateReq;
import com.kgquiz.quiz.service.ReportService;
import com.kgquiz.quiz.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 学生档案：教师/管理员可管理全部；学生本人只能读自己的图谱、报告与错题本。
 */
@RestController
@RequestMapping("/api/students")
@RequireRole({UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;
    private final ReportService reportService;

    /** 学生名册（教师/管理员） */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(studentService.list());
    }

    /** 新建学生：复制图谱 + 同事务创建学生登录账号 */
    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody StudentCreateReq req) {
        return R.ok(studentService.create(req));
    }

    /** 学生图谱视图：学生本人可访问，且仅限自己的 studentId */
    @GetMapping("/{id}/graph")
    @RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> graph(@PathVariable Long id) {
        UserContext.checkStudentScope(id);
        return R.ok(studentService.studentGraph(id));
    }

    /** 学情报告：掌握度分布、知识点明细、历次练习趋势、补强建议 */
    @GetMapping("/{id}/report")
    @RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> report(@PathVariable Long id) {
        UserContext.checkStudentScope(id);
        return R.ok(reportService.report(id));
    }

    /** 错题本：按题目聚合，含错误次数、最近错因、解析 */
    @GetMapping("/{id}/wrong-questions")
    @RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> wrongQuestions(@PathVariable Long id) {
        UserContext.checkStudentScope(id);
        return R.ok(reportService.wrongQuestions(id));
    }

    /** 人工修正/重置某知识点掌握度（仅教师/管理员，学生不能改自己的分） */
    @PutMapping("/{id}/mastery")
    public R<Map<String, Object>> adjustMastery(@PathVariable Long id,
                                                 @Valid @RequestBody MasteryAdjustReq req) {
        return R.ok(studentService.adjustMastery(id, req.nodeId(), req.mastery()));
    }

    /** 重置学生登录密码（教师/管理员） */
    @PutMapping("/{id}/password")
    public R<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                @RequestBody Map<String, String> body) {
        return R.ok(studentService.resetPassword(id, body.get("password")));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        studentService.delete(id);
        return R.ok();
    }
}
