package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.DiagnosisGenerateReq;
import com.kgquiz.quiz.service.DiagnosisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 入学诊断：一次覆盖全部知识点的摸底测验 + 诊断报告。
 *
 * <p>诊断卷本身依然是普通作业（走 /api/exams/my/{examId} 作答与交卷），
 * 这里只负责「生成」与「看报告」两件事。</p>
 */
@RestController
@RequestMapping("/api/diagnosis")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    /** 教师 / 机构管理员 / 平台管理员：给学生生成诊断卷并直接指派 */
    @PostMapping("/generate")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> generate(@Valid @RequestBody DiagnosisGenerateReq req) {
        UserContext.checkStudentScope(req.studentId());
        return R.ok(diagnosisService.generate(req.studentId(), req.perNode(), req.maxQuestions()));
    }

    /** 诊断报告：逐知识点得分率、掌握度起点、薄弱 / 已掌握清单与学习起点推荐 */
    @GetMapping("/{studentId}/report")
    public R<Map<String, Object>> report(@PathVariable Long studentId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(diagnosisService.report(studentId));
    }

    /** 学生看自己的诊断报告 */
    @GetMapping("/my/report")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> myReport() {
        return R.ok(diagnosisService.report(requireOwnStudentId()));
    }

    /** 学生自助开始诊断：给自己生成一份覆盖全部知识点的诊断卷（学生端「诊」的入口） */
    @PostMapping("/my/generate")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> myGenerate(@RequestParam(required = false) Integer perNode,
                                             @RequestParam(required = false) Integer maxQuestions) {
        return R.ok(diagnosisService.generate(requireOwnStudentId(), perNode, maxQuestions));
    }

    private Long requireOwnStudentId() {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return current.studentId();
    }
}
