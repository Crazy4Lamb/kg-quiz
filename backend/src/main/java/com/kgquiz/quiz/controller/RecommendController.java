package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.RecommendRuleReq;
import com.kgquiz.quiz.dto.RecommendSimulateReq;
import com.kgquiz.quiz.service.MasteryProfileService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点学习推荐：规则配置、沙盒模拟、推荐结果、掌握程度画像。
 *
 * <p>接口分两类边界：{@code /rules} 与 {@code /simulate} 是管理能力（教师/管理员），
 * {@code /my*} 与 {@code /records/*} 是学习数据（学生看自己、教师看自己带的）。
 * 后者一律过 {@link UserContext#checkStudentScope}，学生显式传别人的 id 直接 403。</p>
 */
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final MasteryProfileService masteryProfileService;

    // ==================== 规则管理 ====================

    /** 参数元数据：分组、取值范围、说明。配置面板靠它动态渲染，不在前端硬编码字段 */
    @GetMapping("/params")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> params() {
        return R.ok(recommendService.paramMeta());
    }

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
        return R.ok(recommendService.saveRule(req.id(), req.orgId(), req.name(), req.strategy(),
                req.config(), req.enabled(), req.isDefault()));
    }

    @DeleteMapping("/rules/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteRule(@PathVariable Long id) {
        recommendService.deleteRule(id);
        return R.ok();
    }

    // ==================== 沙盒模拟 ====================

    /**
     * 用草稿配置模拟推荐结果，<b>不落库</b>。
     *
     * <p>同时返回「当前生效配置」的结果做对照，答三个问题：
     * 改了权重后哪些知识点进来了、哪些掉出去了、排名的前后位移。</p>
     */
    @PostMapping("/simulate")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> simulate(@RequestBody RecommendSimulateReq req) {
        Long studentId = req.studentId();
        UserContext.checkStudentScope(studentId);

        RecommendService.Strategy draftStrategy = RecommendService.Strategy.parse(req.strategy());
        RecommendService.Config draft = req.config() == null || req.config().isEmpty()
                ? RecommendService.Config.preset(draftStrategy)
                : recommendService.sanitize(req.config(), draftStrategy);

        List<RecommendService.Recommendation> current = recommendService.recommend(studentId, req.limit());
        List<RecommendService.Recommendation> simulated =
                recommendService.recommendWith(studentId, req.limit(), draft, draftStrategy);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("strategy", draftStrategy.name());
        out.put("strategyLabel", draftStrategy.label);
        out.put("draftConfig", draft.toMap());
        out.put("current", current.stream().map(RecommendService.Recommendation::toMap).toList());
        out.put("simulated", simulated.stream().map(RecommendService.Recommendation::toMap).toList());
        out.put("diff", diff(current, simulated));
        return R.ok(out);
    }

    /**
     * 对比两版推荐结果。
     *
     * <p>三类变化分开报，因为它们代表三种不同的问题：
     * 新进（调参调出了新目标）、掉出（把原来的目标挤掉了）、位移（顺序变了但还在）。</p>
     */
    private Map<String, Object> diff(List<RecommendService.Recommendation> before,
                                     List<RecommendService.Recommendation> after) {
        Map<Long, Integer> beforeRank = new LinkedHashMap<>();
        for (int i = 0; i < before.size(); i++) {
            beforeRank.put(before.get(i).nodeId(), i + 1);
        }
        List<Map<String, Object>> entered = new ArrayList<>();
        List<Map<String, Object>> left = new ArrayList<>();
        List<Map<String, Object>> moved = new ArrayList<>();
        int gained = 0;
        int lost = 0;

        for (int i = 0; i < after.size(); i++) {
            RecommendService.Recommendation r = after.get(i);
            Integer old = beforeRank.get(r.nodeId());
            Map<String, Object> m = r.toMap();
            m.put("newRank", i + 1);
            m.put("oldRank", old);
            if (old == null) {
                entered.add(m);
                gained++;
            } else if (old != i + 1) {
                m.put("delta", old - (i + 1));
                moved.add(m);
            }
        }
        Set<Long> afterIds = new LinkedHashSet<>();
        after.forEach(r -> afterIds.add(r.nodeId()));
        for (int i = 0; i < before.size(); i++) {
            RecommendService.Recommendation r = before.get(i);
            if (!afterIds.contains(r.nodeId())) {
                Map<String, Object> m = r.toMap();
                m.put("oldRank", i + 1);
                left.add(m);
                lost++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entered", entered);
        out.put("left", left);
        out.put("moved", moved);
        out.put("gainedCount", gained);
        out.put("lostCount", lost);
        out.put("movedCount", moved.size());
        out.put("changed", gained > 0 || lost > 0 || !moved.isEmpty());
        return out;
    }

    // ==================== 推荐结果 ====================

    /** 教师 / 管理员：预览某个学生的推荐学习顺序 */
    @GetMapping("/preview")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> preview(@RequestParam Long studentId,
                                          @RequestParam(required = false) Integer limit) {
        UserContext.checkStudentScope(studentId);
        return R.ok(detail(studentId, limit));
    }

    /** 学生：我该按什么顺序学 */
    @GetMapping("/my")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> mine(@RequestParam(required = false) Integer limit) {
        Long studentId = currentStudentId();
        return R.ok(detail(studentId, limit));
    }

    private Map<String, Object> detail(Long studentId, Integer limit) {
        List<RecommendService.Recommendation> recs = recommendService.recommend(studentId, limit);
        Long orgId = masteryProfileService.orgIdOf(studentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("strategy", recommendService.strategyOf(orgId).name());
        out.put("strategyLabel", recommendService.strategyOf(orgId).label);
        out.put("recommendations", recs.stream().map(RecommendService.Recommendation::toMap).toList());
        out.put("summary", summarize(recs));
        return out;
    }

    /** 分档统计，让页面顶部能一句话说清「今天该学几个、复习几个」 */
    private Map<String, Object> summarize(List<RecommendService.Recommendation> recs) {
        int weak = 0;
        int mid = 0;
        int solid = 0;
        int unseen = 0;
        for (RecommendService.Recommendation r : recs) {
            if ("WEAK".equals(r.tier())) {
                weak++;
            } else if ("MID".equals(r.tier())) {
                mid++;
            } else if ("SOLID".equals(r.tier())) {
                solid++;
            } else {
                unseen++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", recs.size());
        m.put("weak", weak);
        m.put("mid", mid);
        m.put("solid", solid);
        m.put("unseen", unseen);
        return m;
    }

    // ==================== 掌握程度画像 ====================

    /** 教师 / 管理员：某个学生的掌握程度总览（分级分布 + 薄弱清单 + 低置信清单） */
    @GetMapping("/mastery/{studentId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> masteryOf(@PathVariable Long studentId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(masteryOverview(studentId));
    }

    /** 学生：我的掌握程度总览 */
    @GetMapping("/my/mastery")
    @RequireRole(UserRole.STUDENT)
    public R<Map<String, Object>> myMastery() {
        return R.ok(masteryOverview(currentStudentId()));
    }

    /**
     * 掌握程度总览的统一入口。
     *
     * <p>配置在这里取一次再传下去：分级阈值必须与推荐用同一份规则，
     * 否则会出现「报告说入门、推荐却按薄弱处理」这种自相矛盾。</p>
     */
    private Map<String, Object> masteryOverview(Long studentId) {
        Long orgId = masteryProfileService.orgIdOf(studentId);
        return masteryProfileService.overview(studentId, masteryProfileService.graphIdOf(studentId),
                recommendService.configOf(orgId));
    }

    /** 单个知识点的掌握画像（含遗忘衰减与置信度），用于详情抽屉 */
    @GetMapping("/mastery/{studentId}/node/{nodeId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN, UserRole.STUDENT})
    public R<Map<String, Object>> nodeMastery(@PathVariable Long studentId, @PathVariable Long nodeId) {
        UserContext.checkStudentScope(studentId);
        RecommendService.Config cfg = recommendService.configOf(masteryProfileService.orgIdOf(studentId));
        return R.ok(masteryProfileService.profile(studentId, nodeId, cfg).toMap());
    }

    private Long currentStudentId() {
        CurrentUser current = UserContext.require();
        if (current.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        return current.studentId();
    }
}
