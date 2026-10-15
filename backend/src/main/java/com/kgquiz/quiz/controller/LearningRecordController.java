package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.service.LearningRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识点学习记录：时间线、统计、单知识点轨迹。
 *
 * <p>与 {@link com.kgquiz.quiz.controller.RecommendController} 分开是因为职责不同：
 * 推荐回答「该学什么」，记录回答「他实际做了什么、变成什么样了」。</p>
 *
 * <p>所有接口都要过归属校验：学生只能看自己，教师看自己带的。</p>
 */
@RestController
@RequestMapping("/api/learning-records")
@RequiredArgsConstructor
public class LearningRecordController {

    private final LearningRecordService learningRecordService;

    /** 学生：我的学习时间线 */
    @GetMapping("/my")
    @RequireRole(UserRole.STUDENT)
    public R<List<Map<String, Object>>> mine(@RequestParam(required = false) String action,
                                             @RequestParam(required = false) Long nodeId,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Integer days) {
        return R.ok(learningRecordService.timeline(currentStudentId(), action, nodeId, limit, days));
    }

    /** 学生：我的学习统计（默认最近 7 天） */
    @GetMapping("/my/stats")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> myStats(@RequestParam(required = false) Integer days) {
        return R.ok(learningRecordService.stats(currentStudentId(), days));
    }

    /** 教师 / 管理员：某个学生的学习时间线 */
    @GetMapping("/student/{studentId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> ofStudent(@PathVariable Long studentId,
                                                  @RequestParam(required = false) String action,
                                                  @RequestParam(required = false) Long nodeId,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Integer days) {
        return R.ok(learningRecordService.timeline(studentId, action, nodeId, limit, days));
    }

    /** 教师 / 管理员：某个学生的学习统计 */
    @GetMapping("/student/{studentId}/stats")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> statsOfStudent(@PathVariable Long studentId,
                                                 @RequestParam(required = false) Integer days) {
        return R.ok(learningRecordService.stats(studentId, days));
    }

    /** 单个知识点上的完整轨迹：这个孩子在这个知识点上经历了什么 */
    @GetMapping("/student/{studentId}/node/{nodeId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN, UserRole.STUDENT})
    public R<Map<String, Object>> nodeTimeline(@PathVariable Long studentId, @PathVariable Long nodeId) {
        return R.ok(learningRecordService.nodeTimeline(studentId, nodeId));
    }

    private Long currentStudentId() {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return current.studentId();
    }
}
