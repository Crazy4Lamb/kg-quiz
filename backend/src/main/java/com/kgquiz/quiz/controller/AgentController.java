package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.AgentSaveReq;
import com.kgquiz.quiz.dto.AgentToolBindReq;
import com.kgquiz.quiz.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置域管理台。
 *
 * <p>三层边界：<b>工具</b>是代码（{@code AgentToolCatalog}，这里只读展示）、
 * <b>编排</b>可配置（本类负责）、<b>权限</b>是运行时算的（不在此暴露任何配置入口）。</p>
 *
 * <p>写接口限 ADMIN / ORG_ADMIN，且平台级 Agent 仅 ADMIN 可动；
 * 目标学生的归属校验走 {@code UserContext.checkStudentScope}。</p>
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    // ==================== 只读 ====================

    /** 工具注册表：工具定义（代码）+ 被哪些 Agent 引用（库）+ 调用统计（库） */
    @GetMapping("/tools")
    @RequireRole({UserRole.ORG_ADMIN, UserRole.TEACHER, UserRole.ADMIN})
    public R<List<Map<String, Object>>> tools() {
        return R.ok(agentService.toolRegistry());
    }

    /** Agent 列表：本机构 + 平台级 */
    @GetMapping
    @RequireRole({UserRole.ORG_ADMIN, UserRole.ADMIN})
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long orgId) {
        return R.ok(agentService.listAgents(orgId));
    }

    /** 我当前这个角色能看到的 Agent（面向前端的入口） */
    @GetMapping("/mine")
    @RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ORG_ADMIN, UserRole.ADMIN})
    public R<List<Map<String, Object>>> mine() {
        CurrentUser user = UserContext.require();
        return R.ok(agentService.agentsForRole(user.role()));
    }

    // ==================== 编排配置（管理员）====================

    @PostMapping
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> save(@RequestBody AgentSaveReq req) {
        return R.ok(agentService.saveAgent(req.id(), req.code(), req.orgId(), req.name(), req.description(),
                req.targetRole(), req.systemPrompt(), req.model(), req.temperature(), req.maxTokens(),
                req.enabled() == null ? 1 : (req.enabled() ? 1 : 0)));
    }

    @PutMapping("/{id}/enabled")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> setEnabled(@PathVariable Long id, @RequestParam Integer enabled) {
        return R.ok(agentService.setEnabled(id, enabled));
    }

    @PostMapping("/{id}/tools")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> bindTools(@PathVariable Long id, @RequestBody AgentToolBindReq req) {
        return R.ok(agentService.bindTools(id, req.toolKeys()));
    }

    @DeleteMapping("/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return R.ok();
    }

    // ==================== 上下文预演 ====================

    /**
     * 上下文预演：以目标学生的身份把该 Agent 挂载的只读工具真跑一遍，
     * 用来回答「这个 Agent 到底能拿到哪些数据」。不调大模型、不写业务数据。
     */
    @PostMapping("/{code}/dry-run")
    @RequireRole({UserRole.ORG_ADMIN, UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> dryRun(@PathVariable String code, @RequestParam Long studentId) {
        if (studentId == null) {
            throw new ForbiddenException("请指定要预演的学生");
        }
        return R.ok(agentService.dryRun(code, studentId));
    }
}