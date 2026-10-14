package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.entity.AgentCallLog;
import com.kgquiz.quiz.entity.AgentProfile;
import com.kgquiz.quiz.entity.AgentTool;
import com.kgquiz.quiz.mapper.AgentCallLogMapper;
import com.kgquiz.quiz.mapper.AgentProfileMapper;
import com.kgquiz.quiz.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Agent 配置域的编排层。
 *
 * <p>三层边界在这里落地：</p>
 * <ul>
 *   <li><b>工具是代码</b> —— 见 {@link AgentToolCatalog}，不落库；</li>
 *   <li><b>编排是数据</b> —— {@code agent_profile} + {@code agent_tool}，可配置；</li>
 *   <li><b>权限是运行时算的</b> —— 本类<b>不提供</b>任何「指定数据范围」的接口。
 *       预演 {@link #dryRun} 只是以某个学生的身份把只读工具跑一遍，
 *       能看到什么完全由下游 Service 按 {@code UserContext} 决定。</li>
 * </ul>
 *
 * <p>换句话说：<b>配置只能决定「看不看得到这个按钮」，决定不了「能不能拿到这份数据」。</b></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final int PREVIEW_LIMIT = 5;

    private final AgentProfileMapper agentProfileMapper;
    private final AgentToolMapper agentToolMapper;
    private final AgentCallLogMapper agentCallLogMapper;

    private final DiagnosisService diagnosisService;
    private final LearningTaskService learningTaskService;
    private final RecommendService recommendService;
    private final ContentService contentService;
    private final QuestionService questionService;
    private final GraphAnalysisService graphAnalysisService;
    private final SearchService searchService;
    private final StudentService studentService;

    // ==================== 工具注册表（只读）====================

    /**
     * 工具注册表：工具定义 + 被哪些 Agent 引用 + 调用观测统计。
     *
     * <p>工具定义来自代码，引用关系与统计来自库，三者合起来才是管理台需要的完整视图。</p>
     */
    public List<Map<String, Object>> toolRegistry() {
        Long scope = UserContext.scopeOrgId();
        List<AgentProfile> agents = listProfiles(scope);

        // toolKey -> 引用它的 Agent 编码
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        Map<String, Integer> usedByWritable = new LinkedHashMap<>();
        for (AgentProfile agent : agents) {
            for (AgentTool bind : agentToolMapper.selectList(
                    new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, agent.getId()))) {
                AgentToolCatalog.Tool tool = AgentToolCatalog.byKey(bind.getToolKey());
                if (tool == null) {
                    continue;
                }
                usedBy.computeIfAbsent(tool.key(), k -> new ArrayList<>()).add(agent.getCode());
                if (tool.sideEffect() == AgentToolCatalog.SideEffect.WRITE) {
                    usedByWritable.merge(tool.key(), 1, Integer::sum);
                }
            }
        }

        Map<String, Map<String, Object>> stats = callStats();

        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentToolCatalog.Tool tool : AgentToolCatalog.ALL) {
            Map<String, Object> m = tool.toMap();
            m.put("usedByAgents", usedBy.getOrDefault(tool.key(), List.of()));
            m.put("usedByCount", usedBy.getOrDefault(tool.key(), List.of()).size());
            // 写工具若被多个 Agent 挂着，在管理台额外提示一次
            m.put("riskHint", tool.sideEffect() == AgentToolCatalog.SideEffect.WRITE
                    && usedByWritable.getOrDefault(tool.key(), 0) > 1
                    ? "有 " + usedByWritable.get(tool.key()) + " 个 Agent 挂载了写工具，确认这是否是必要的"
                    : "");
            m.putAll(stats.getOrDefault(tool.key(), Map.of()));
            out.add(m);
        }
        return out;
    }

    /** 工具调用统计：次数 / 成功率 / 平均耗时 */
    private Map<String, Map<String, Object>> callStats() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (AgentCallLog row : agentCallLogMapper.selectList(null)) {
            Map<String, Object> s = out.computeIfAbsent(row.getToolKey(),
                    k -> new LinkedHashMap<>(Map.of("callCount", 0, "okCount", 0, "costSum", 0L)));
            s.put("callCount", ((int) s.get("callCount")) + 1);
            if (row.getOk() != null && row.getOk() == 1) {
                s.put("okCount", ((int) s.get("okCount")) + 1);
            }
            s.put("costSum", ((long) s.get("costSum")) + (row.getCostMs() == null ? 0L : row.getCostMs()));
        }
        for (Map<String, Object> s : out.values()) {
            int count = (int) s.get("callCount");
            long sum = (long) s.get("costSum");
            s.put("successRate", count == 0 ? null : Math.round(((int) s.get("okCount")) * 1000.0 / count) / 10.0);
            s.put("avgCostMs", count == 0 ? null : sum / count);
            s.remove("costSum");
        }
        return out;
    }

    // ==================== Agent 编排配置 ====================

    /** 本机构 Agent + 平台级 Agent */
    private List<AgentProfile> listProfiles(Long orgId) {
        LambdaQueryWrapper<AgentProfile> q = new LambdaQueryWrapper<>();
        if (orgId != null) {
            q.in(AgentProfile::getOrgId, orgId, (Long) null);
        }
        return agentProfileMapper.selectList(q);
    }

    public List<Map<String, Object>> listAgents(Long orgIdFilter) {
        Long scope = UserContext.scopeOrgId();
        // 显式传了 orgId 就要落在可见范围内
        if (orgIdFilter != null) {
            UserContext.checkOrgScope(orgIdFilter);
        }
        Long filter = orgIdFilter != null ? orgIdFilter : scope;
        List<AgentProfile> profiles = listProfiles(filter);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : profiles) {
            out.add(toView(p));
        }
        return out;
    }

    private Map<String, Object> toView(AgentProfile p) {
        List<AgentTool> binds = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, p.getId())
                        .orderByAsc(AgentTool::getSortOrder));
        List<Map<String, Object>> tools = new ArrayList<>();
        int writeCount = 0;
        for (AgentTool b : binds) {
            AgentToolCatalog.Tool t = AgentToolCatalog.byKey(b.getToolKey());
            if (t == null) {
                continue;
            }
            if (t.sideEffect() == AgentToolCatalog.SideEffect.WRITE) {
                writeCount++;
            }
            Map<String, Object> tm = t.toMap();
            tm.put("boundEnabled", b.getEnabled());
            tm.put("paramOverride", b.getParamOverride());
            tools.add(tm);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("orgId", p.getOrgId());
        m.put("platformLevel", p.getOrgId() == null);
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("targetRole", p.getTargetRole());
        m.put("model", p.getModel());
        m.put("temperature", p.getTemperature());
        m.put("maxTokens", p.getMaxTokens());
        m.put("enabled", p.getEnabled());
        m.put("tools", tools);
        m.put("toolCount", tools.size());
        m.put("writeToolCount", writeCount);
        return m;
    }

    /** 新建 / 更新 Agent 编排配置。二期开放 systemPrompt 在线编辑。 */
    @Transactional
    public Map<String, Object> saveAgent(Long id, String code, Long orgId, String name, String description,
                                         String targetRole, String systemPrompt, String model,
                                         Double temperature, Integer maxTokens, Integer enabled) {
        CurrentUser user = UserContext.require();
        if (!user.isAdmin() && !user.isOrgAdmin()) {
            throw new ForbiddenException("只有管理员可以配置 Agent");
        }
        if (name == null || name.isBlank()) {
            throw new BizException("Agent 名称不能为空");
        }
        String role = UserRole.of(targetRole).name();
        if (orgId == null && !user.isAdmin()) {
            throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
        }
        Long writeOrg = orgId == null ? null : UserContext.resolveWriteOrgId(orgId);

        AgentProfile p;
        if (id != null) {
            p = require(id);
            UserContext.checkOrgScope(p.getOrgId());
            if (p.getOrgId() == null && !user.isAdmin()) {
                throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
            }
        } else {
            if (code == null || code.isBlank()) {
                throw new BizException("Agent 编码不能为空");
            }
            AgentProfile dup = agentProfileMapper.selectOne(
                    new LambdaQueryWrapper<AgentProfile>().eq(AgentProfile::getCode, code.trim())
                            .last("LIMIT 1"));
            if (dup != null) {
                throw new BizException("Agent 编码已存在：" + code.trim());
            }
            p = new AgentProfile();
            p.setCode(code.trim());
            p.setCreatedAt(java.time.LocalDateTime.now());
        }
        p.setOrgId(writeOrg);
        p.setName(name.trim());
        p.setDescription(description == null ? "" : description.trim());
        p.setTargetRole(role);
        p.setSystemPrompt(systemPrompt);
        p.setModel(model == null ? "" : model.trim());
        p.setTemperature(temperature == null ? 0.3 : temperature);
        p.setMaxTokens(maxTokens == null ? 2048 : maxTokens);
        p.setEnabled(enabled == null ? 1 : enabled);
        p.setUpdatedAt(java.time.LocalDateTime.now());
        if (id == null) {
            agentProfileMapper.insert(p);
        } else {
            agentProfileMapper.updateById(p);
        }
        return toView(p);
    }

    /** 启停 */
    @Transactional
    public Map<String, Object> setEnabled(Long id, Integer enabled) {
        AgentProfile p = require(id);
        UserContext.checkOrgScope(p.getOrgId());
        if (p.getOrgId() == null && !UserContext.require().isAdmin()) {
            throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
        }
        p.setEnabled(enabled == null ? 1 : (enabled == 0 ? 0 : 1));
        p.setUpdatedAt(java.time.LocalDateTime.now());
        agentProfileMapper.updateById(p);
        return toView(p);
    }

    /** 全量覆盖工具绑定 */
    @Transactional
    public Map<String, Object> bindTools(Long id, List<String> toolKeys) {
        AgentProfile p = require(id);
        UserContext.checkOrgScope(p.getOrgId());
        if (p.getOrgId() == null && !UserContext.require().isAdmin()) {
            throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
        }
        // 只接受工具目录里存在的 key —— 配置不了「目录里没有的工具」
        Set<String> valid = new LinkedHashSet<>();
        if (toolKeys != null) {
            for (String k : toolKeys) {
                AgentToolCatalog.Tool t = AgentToolCatalog.byKey(k);
                if (t == null) {
                    throw new BizException("工具不存在于目录中：" + k);
                }
                valid.add(t.key());
            }
        }
        agentToolMapper.delete(new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, id));
        int order = 0;
        for (String key : valid) {
            AgentTool t = new AgentTool();
            t.setAgentId(id);
            t.setToolKey(key);
            t.setEnabled(1);
            t.setSortOrder(order++);
            agentToolMapper.insert(t);
        }
        return toView(p);
    }

    @Transactional
    public void delete(Long id) {
        AgentProfile p = require(id);
        UserContext.checkOrgScope(p.getOrgId());
        if (p.getOrgId() == null && !UserContext.require().isAdmin()) {
            throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
        }
        agentToolMapper.delete(new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, id));
        agentProfileMapper.deleteById(id);
    }

    public AgentProfile require(Long id) {
        AgentProfile p = agentProfileMapper.selectById(id);
        if (p == null) {
            throw new BizException("Agent 不存在: " + id);
        }
        return p;
    }

    public AgentProfile requireByCode(String code) {
        AgentProfile p = agentProfileMapper.selectOne(
                new LambdaQueryWrapper<AgentProfile>().eq(AgentProfile::getCode, code).last("LIMIT 1"));
        if (p == null) {
            throw new BizException("Agent 不存在: " + code);
        }
        return p;
    }

    // ==================== 上下文预演（一期无 LLM 的「试运行」）====================

    /**
     * 上下文预演：<b>以指定学生的身份</b>把该 Agent 挂载的只读工具真跑一遍。
     *
     * <p>它回答的是「这个 Agent 以某个人的身份，<b>到底能拿到什么数据</b>」，
     * 而不是「它会怎么回答」—— 后者要等 Agent 运行时接上才有意义。
     * 因此预演<b>不调用大模型、不写任何业务数据</b>，
     * 只往 {@code agent_call_log} 记一条观测。</p>
     *
     * <p>参数由上下文推导：需要 nodeId 的工具用「推荐列表首个知识点」，
     * 需要 keyword 的工具用「该知识点的名称」，需要 graphId 的用「学生所在图谱」。
     * 这样一次预演就能覆盖整条只读链路。</p>
     */
    public Map<String, Object> dryRun(String code, Long studentId) {
        AgentProfile agent = requireByCode(code);
        // 机构边界：平台级 Agent 谁都能看，本机构 Agent 仅本机构
        UserContext.checkOrgScope(agent.getOrgId());
        // 目标学生的归属校验 —— 沿用既有边界，教师只能预演自己范围内的学生
        UserContext.checkStudentScope(studentId);

        List<AgentTool> binds = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agent.getId())
                        .eq(AgentTool::getEnabled, 1)
                        .orderByAsc(AgentTool::getSortOrder));

        // 预演上下文：按需推导，避免一开始就打一堆无用查询
        List<RecommendService.Recommendation> recs = List.of();
        Long focusNodeId = null;
        String focusKeyword = null;
        Long graphId = null;
        try {
            recs = recommendService.recommend(studentId, 12);
            if (!recs.isEmpty()) {
                focusNodeId = recs.get(0).nodeId();
                focusKeyword = recs.get(0).nodeName();
            }
        } catch (Exception e) {
            log.debug("dry-run 推荐上下文获取失败: {}", e.getMessage());
        }
        try {
            Map<String, Object> sg = studentService.studentGraph(studentId);
            Object gid = sg == null ? null : sg.get("graphId");
            if (gid instanceof Number n) {
                graphId = n.longValue();
            }
        } catch (Exception e) {
            log.debug("dry-run 图谱上下文获取失败: {}", e.getMessage());
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        int okCount = 0;
        int skippedCount = 0;
        for (AgentTool b : binds) {
            AgentToolCatalog.Tool tool = AgentToolCatalog.byKey(b.getToolKey());
            if (tool == null) {
                continue;
            }
            if (!tool.dryRunnable()) {
                steps.add(step(tool, "SKIPPED", 0,
                        Map.of("reason", "写工具不参与预演：预演必须零副作用"), null));
                skippedCount++;
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                Map<String, Object> brief = invoke(tool.key(), studentId, focusNodeId, focusKeyword, graphId);
                long cost = System.currentTimeMillis() - start;
                boolean skipped = brief.containsKey("skipped");
                steps.add(step(tool, skipped ? "SKIPPED" : "OK", cost, brief, null));
                if (skipped) {
                    skippedCount++;
                } else {
                    okCount++;
                }
                log(agent.getId(), tool.key(), studentId, skipped ? 1 : 1, cost, null);
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                steps.add(step(tool, "ERROR", cost, Map.of(), err));
                log(agent.getId(), tool.key(), studentId, 0, cost, err);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentCode", agent.getCode());
        out.put("agentName", agent.getName());
        out.put("targetStudentId", studentId);
        out.put("identityNote", "预演以 studentId=" + studentId + " 的学生身份执行，"
                + "能看到哪些数据由后端 UserContext 实时判定，与本配置无关");
        out.put("focusNodeId", focusNodeId);
        out.put("focusKeyword", focusKeyword);
        out.put("graphId", graphId);
        out.put("steps", steps);
        out.put("total", steps.size());
        out.put("okCount", okCount);
        out.put("skippedCount", skippedCount);
        out.put("errorCount", steps.size() - okCount - skippedCount);
        return out;
    }

    private Map<String, Object> step(AgentToolCatalog.Tool tool, String status, long cost,
                                     Map<String, Object> brief, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolKey", tool.key());
        m.put("toolName", tool.name());
        m.put("category", tool.category());
        m.put("sideEffect", tool.sideEffect().name());
        m.put("sideEffectLabel", tool.sideEffect().label());
        m.put("status", status);
        m.put("costMs", cost);
        m.put("brief", brief);
        m.put("error", error);
        return m;
    }

    /** 真正调下游 Service。返回的是「摘要」而不是原始大对象——管理台要的是能一眼看懂。 */
    private Map<String, Object> invoke(String toolKey, Long studentId, Long nodeId,
                                        String keyword, Long graphId) {
        Map<String, Object> brief = new LinkedHashMap<>();
        switch (toolKey) {
            case "diagnosis_my_report", "diagnosis_report" -> {
                Map<String, Object> r = diagnosisService.report(studentId);
                brief.put("summary", "逐知识点得分率 / 掌握度起点 / 薄弱与已掌握清单");
                brief.put("weakCount", size(r.get("weakNodes")));
                brief.put("solidCount", size(r.get("solidNodes")));
                brief.put("uncoveredCount", size(r.get("uncoveredNodes")));
                brief.put("scoredNodes", size(r.get("nodeResults")));
            }
            case "my_tasks_today", "student_tasks" -> {
                Map<String, Object> t = learningTaskService.overview(studentId);
                brief.put("summary", "诊断 → 学 → 练 → 复习 任务清单");
                Object pending = t.get("pending");
                Object done = t.get("done");
                brief.put("pendingCount", pending instanceof List<?> l ? l.size() : 0);
                brief.put("doneCount", done instanceof List<?> l ? l.size() : 0);
                brief.put("hasDiagnosis", t.get("diagnosisCompleted"));
            }
            case "my_recommend" -> {
                List<RecommendService.Recommendation> recs = recommendService.recommend(studentId, PREVIEW_LIMIT);
                brief.put("summary", "按掌握度缺口与知识点重要性排序的学习优先级");
                brief.put("count", recs.size());
                if (!recs.isEmpty()) {
                    RecommendService.Recommendation top = recs.get(0);
                    brief.put("topNode", top.nodeName());
                    brief.put("topReason", top.reasons().isEmpty() ? "" : top.reasons().get(0));
                }
            }
            case "published_content" -> {
                if (nodeId == null) {
                    brief.put("skipped", true);
                    brief.put("reason", "拿不到上下文知识点（该学生可能还没有掌握度数据）");
                    return brief;
                }
                List<Map<String, Object>> items = contentService.studentContents(nodeId);
                brief.put("summary", "该知识点已发布的教学内容（草稿不可见）");
                brief.put("nodeId", nodeId);
                brief.put("count", items.size());
            }
            case "search_nodes" -> {
                if (keyword == null || keyword.isBlank()) {
                    brief.put("skipped", true);
                    brief.put("reason", "缺少检索关键词");
                    return brief;
                }
                Map<String, Object> r = searchService.search(null, keyword, null, null);
                brief.put("summary", "知识图谱节点检索");
                brief.put("keyword", keyword);
                brief.put("total", r.get("total"));
            }
            case "search_questions" -> {
                if (keyword == null || keyword.isBlank()) {
                    brief.put("skipped", true);
                    brief.put("reason", "缺少检索关键词");
                    return brief;
                }
                Map<String, Object> r = questionService.list(null, null, null, keyword, null, 1, 10);
                Object list = r.get("list");
                brief.put("summary", "题库检索");
                brief.put("keyword", keyword);
                brief.put("total", r.get("total"));
                brief.put("returned", list instanceof List<?> l ? l.size() : 0);
            }
            case "graph_analysis" -> {
                if (graphId == null) {
                    brief.put("skipped", true);
                    brief.put("reason", "拿不到学生所在图谱");
                    return brief;
                }
                Map<String, Object> r = graphAnalysisService.pageRank(graphId, false, PREVIEW_LIMIT);
                Object items = r.get("items");
                brief.put("summary", "知识点核心度排序");
                brief.put("graphId", graphId);
                brief.put("returned", items instanceof List<?> l ? l.size() : 0);
            }
            default -> {
                brief.put("skipped", true);
                brief.put("reason", "该工具暂无预演实现");
            }
        }
        return brief;
    }

    private int size(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    private void log(Long agentId, String toolKey, Long studentId, int ok, long cost, String error) {
        try {
            CurrentUser user = UserContext.peek();
            AgentCallLog row = new AgentCallLog();
            row.setAgentId(agentId);
            row.setToolKey(toolKey);
            row.setOperatorUserId(user == null ? null : user.userId());
            row.setTargetStudentId(studentId);
            row.setOk(ok);
            row.setCostMs(cost);
            row.setError(error == null ? null : (error.length() > 250 ? error.substring(0, 250) : error));
            row.setCreatedAt(java.time.LocalDateTime.now());
            agentCallLogMapper.insert(row);
        } catch (Exception e) {
            // 观测写失败不该影响预演本身
            log.warn("写工具调用观测失败: {}", e.getMessage());
        }
    }

    // ==================== 种子 ====================

    /**
     * 启动时补齐三个平台级 Agent（已存在则跳过），做法与 {@code AuthService} 补默认账号一致。
     * 只绑只读工具 —— 写工具等二期接入人工确认流程后再挂。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void seedDefaults() {
        seed("student-planner", "学生学习规划师", "诊断 → 学 → 练 → 复习，讲清楚下一步先做什么",
                "STUDENT",
                "你是一名学习规划助手。只依据后端返回的数据回答，不编造知识点与分数。"
                        + "掌握度与推荐顺序以 RecommendService 的结果为准，不要自己另算。"
                        + "涉及写入的操作一律先给建议、等用户确认。",
                List.of("diagnosis_my_report", "my_tasks_today", "my_recommend", "published_content", "search_nodes"));
        seed("teacher-assistant", "教师教研助手", "题目知识点识别、教学内容生成与专项出题",
                "TEACHER",
                "你是一名教研助手。题目映射只给候选与置信度，必须由教研确认后才写入。"
                        + "生成的教学内容先落草稿，发布前必须人工过目。",
                List.of("diagnosis_report", "student_tasks", "mapping_suggest", "content_generate",
                        "practice_generate", "search_nodes", "search_questions"));
        seed("admin-governor", "知识治理助手", "图谱结构分析与知识点治理",
                "ADMIN",
                "你是一名知识治理助手。只做结构分析与治理建议，任何写入图谱的操作都要人工确认。",
                List.of("graph_analysis", "search_nodes", "search_questions", "diagnosis_report"));
    }

    private void seed(String code, String name, String description, String role, String prompt, List<String> tools) {
        try {
            if (agentProfileMapper.selectCount(new LambdaQueryWrapper<AgentProfile>()
                    .eq(AgentProfile::getCode, code)) > 0) {
                return;
            }
            AgentProfile p = new AgentProfile();
            p.setCode(code);
            p.setOrgId(null);
            p.setName(name);
            p.setDescription(description);
            p.setTargetRole(role);
            p.setSystemPrompt(prompt);
            p.setModel("");
            p.setTemperature(0.3);
            p.setMaxTokens(2048);
            p.setEnabled(1);
            p.setCreatedAt(java.time.LocalDateTime.now());
            p.setUpdatedAt(java.time.LocalDateTime.now());
            agentProfileMapper.insert(p);

            int order = 0;
            for (String key : tools) {
                AgentToolCatalog.Tool t = AgentToolCatalog.byKey(key);
                if (t == null) {
                    continue;
                }
                AgentTool b = new AgentTool();
                b.setAgentId(p.getId());
                b.setToolKey(key);
                b.setEnabled(1);
                b.setSortOrder(order++);
                agentToolMapper.insert(b);
            }
            log.info("已补齐平台级 Agent: {}", code);
        } catch (Exception e) {
            log.warn("补齐 Agent {} 失败: {}", code, e.getMessage());
        }
    }

    /** 供其他模块复用：某个角色能看到的 Agent（按 targetRole 匹配） */
    public List<Map<String, Object>> agentsForRole(UserRole role) {
        List<AgentProfile> profiles = agentProfileMapper.selectList(
                new LambdaQueryWrapper<AgentProfile>()
                        .eq(AgentProfile::getTargetRole, role.name())
                        .eq(AgentProfile::getEnabled, 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : profiles) {
            if (p.getOrgId() != null && !UserContext.inScope(p.getOrgId())) {
                continue;
            }
            out.add(toView(p));
        }
        return out;
    }
}