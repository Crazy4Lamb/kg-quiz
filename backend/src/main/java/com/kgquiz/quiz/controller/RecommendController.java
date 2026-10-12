package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.RecommendRuleReq;
import com.kgquiz.quiz.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识点学习优先级推荐规则。
 *
 * <p>规则参数（掌握度权重、重要性权重、薄弱 / 掌握线、前置提升、巩固配额、每日任务数）
 * 落库可调，出题引擎与学习任务流共用同一套配置。</p>
 */
@RestController
@RequestMapping("/api/recommend")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    // ==================== 规则管理（管理员）====================

    /** 规则列表：本机构规则 + 平台默认规则 */
    @GetMapping("/rules")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> listRules(@RequestParam(required = false) Long orgId) {
        return R.ok(recommendService.listRules(orgId));
    }

    /** 新建 / 更新规则：机构管理员只能维护本机构的规则（平台默认规则仅平台管理员可动） */
    @PostMapping("/rules")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> saveRule(@RequestBody RecommendRuleReq req) {
        return R.ok(recommendService.saveRule(req.id(), req.orgId(), req.name(), req.config(),
                req.enabled(), req.isDefault()));
    }

    @DeleteMapping("/rules/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteRule(@PathVariable Long id) {
        recommendService.deleteRule(id);
        return R.ok();
    }

    // ==================== 推荐预览 ====================

    /** 教师 / 管理员：预览某个学生的推荐学习顺序 */
    @GetMapping("/preview")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> preview(@RequestParam Long studentId,
                                                @RequestParam(required = false) Integer limit) {
        UserContext.checkStudentScope(studentId);
        return R.ok(recommendService.recommend(studentId, limit).stream()
                .map(RecommendService.Recommendation::toMap).toList());
    }

    /** 学生：我该按什么顺序学 */
    @GetMapping("/my")
    @RequireRole(UserRole.STUDENT)
    public R<List<Map<String, Object>>> mine(@RequestParam(required = false) Integer limit) {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return R.ok(recommendService.recommend(current.studentId(), limit).stream()
                .map(RecommendService.Recommendation::toMap).toList());
    }
}
