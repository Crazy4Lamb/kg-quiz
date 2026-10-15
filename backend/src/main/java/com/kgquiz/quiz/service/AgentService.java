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
import com.kgquiz.quiz.entity.Campus;
import com.kgquiz.quiz.entity.Clazz;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.mapper.AgentCallLogMapper;
import com.kgquiz.quiz.mapper.AgentProfileMapper;
import com.kgquiz.quiz.mapper.AgentToolMapper;
import com.kgquiz.quiz.mapper.CampusMapper;
import com.kgquiz.quiz.mapper.ClazzMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p><b>生效范围（scope）是第四件事，别和权限混为一谈。</b>
 * {@code org_id / campus_id / clazz_id} 三列表达的是「这个 Agent 在哪些组织单元启用」，
 * 属于编排层；它不放开任何数据访问。范围落库前一律经
 * {@link #resolveScope} 做机构归属校验，越界直接 403。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final int PREVIEW_LIMIT = 5;

    private final AgentProfileMapper agentProfileMapper;
    private final AgentToolMapper agentToolMapper;
    private final AgentCallLogMapper agentCallLogMapper;
    private final CampusMapper campusMapper;
    private final ClazzMapper clazzMapper;
    private final StudentMapper studentMapper;

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

    /**
     * 生效范围解析结果。
     *
     * @param orgId   归属机构（平台级为 null）
     * @param campusId 生效校区；null 表示机构内全部
     * @param clazzId 生效班级；null 表示校区/机构内全部
     */
    public record Scope(Long orgId, Long campusId, Long clazzId) {
    }

    /**
     * 把请求里的 orgId / campusId / clazzId 收敛成一组合法范围。
     *
     * <p>规则只有三条，但每条都是硬边界：</p>
     * <ol>
     *   <li><b>不能跳级</b>：给了 clazzId 就必须有 campusId —— 允许反查回填，不允许凭空指定；</li>
     *   <li><b>不能错挂</b>：校区必须属于该机构、班级必须属于该校区，否则 403；</li>
     *   <li><b>平台级不能带校区/班级</b>：orgId 为 null 时这两列必须为空，
     *       否则「全平台生效」和「只对某班生效」会自相矛盾。</li>
     * </ol>
     *
     * <p>注意这仍然只是「在哪里启用」，不放开任何数据访问。</p>
     */
    private Scope resolveScope(Long requestedOrgId, Long campusId, Long clazzId) {
        Long writeOrg = requestedOrgId == null ? null : UserContext.resolveWriteOrgId(requestedOrgId);

        if (clazzId != null) {
            Clazz clazz = clazzMapper.selectById(clazzId);
            if (clazz == null) {
                throw new BizException("班级不存在: " + clazzId);
            }
            // 越界由 checkOrgScope 兜（管理员放行，机构管理员比对自己机构）
            UserContext.checkOrgScope(clazz.getOrgId());
            if (writeOrg != null && !writeOrg.equals(clazz.getOrgId())) {
                throw new ForbiddenException("班级不属于该机构: " + clazzId);
            }
            if (campusId != null && !campusId.equals(clazz.getCampusId())) {
                throw new BizException("班级不属于该校区，请检查校区选择");
            }
            // campusId 允许省略，由班级反查回填 —— 避免前端把同一事实存两遍且可能存不一致
            return new Scope(clazz.getOrgId(), clazz.getCampusId(), clazzId);
        }

        if (campusId != null) {
            Campus campus = campusMapper.selectById(campusId);
            if (campus == null) {
                throw new BizException("校区不存在: " + campusId);
            }
            UserContext.checkOrgScope(campus.getOrgId());
            if (writeOrg != null && !writeOrg.equals(campus.getOrgId())) {
                throw new ForbiddenException("校区不属于该机构: " + campusId);
            }
            return new Scope(campus.getOrgId(), campusId, null);
        }

        if (writeOrg == null && requestedOrgId == null && !UserContext.require().isAdmin()) {
            throw new ForbiddenException("平台级 Agent 仅平台管理员可维护");
        }
        return new Scope(writeOrg, null, null);
    }

    /**
     * 本机构 Agent + 平台级 Agent。
     *
     * <p>这里必须写成 {@code (org_id = ? OR org_id IS NULL)} 而不能用
     * {@code in(orgId, orgId, null)} —— 后者会生成 {@code org_id IN (2, NULL)}，
     * 而 {@code NULL} 在 IN 列表里永远不匹配，平台级 Agent 会被静默漏掉。
     * 这个坑只在「按机构筛选」时才暴露：不筛选时条件整个不加，所以之前一直没发现。</p>
     */
    private List<AgentProfile> listProfiles(Long orgId) {
        LambdaQueryWrapper<AgentProfile> q = new LambdaQueryWrapper<>();
        if (orgId != null) {
            q.and(w -> w.eq(AgentProfile::getOrgId, orgId).or().isNull(AgentProfile::getOrgId));
        }
        return agentProfileMapper.selectList(q);
    }

    /**
     * Agent 列表：本机构 + 平台级，可按校区 / 班级下钻。
     *
     * <p>筛选语义是「<b>在哪些组织单元生效</b>」，不是「谁能看到」：
     * 选中某个校区时，平台级 + 本机构级 + 该校区级 + 该校区下班级级的 Agent 都会列出，
     * 因为它们确实都在这个校区生效。</p>
     */
    public List<Map<String, Object>> listAgents(Long orgIdFilter, Long campusId, Long clazzId) {
        Long scope = UserContext.scopeOrgId();
        // 显式传了 orgId 就要落在可见范围内
        if (orgIdFilter != null) {
            UserContext.checkOrgScope(orgIdFilter);
        }
        if (campusId != null) {
            requireCampus(campusId);
        }
        if (clazzId != null) {
            requireClazz(clazzId);
        }
        Long filter = orgIdFilter != null ? orgIdFilter : scope;
        List<AgentProfile> profiles = listProfiles(filter);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : profiles) {
            if (!matchesScope(p, campusId, clazzId)) {
                continue;
            }
            out.add(toView(p));
        }
        return out;
    }

    /**
     * 该 Agent 是否在给定的校区 / 班级生效。
     *
     * <p>粒度只允许「粗的覆盖细的」：平台级（orgId=null）处处生效，
     * 机构级在本机构处处生效，校区级在本校区处处生效，班级级只在本班生效。</p>
     */
    private boolean matchesScope(AgentProfile p, Long campusId, Long clazzId) {
        if (clazzId != null) {
            if (p.getClazzId() != null) {
                return p.getClazzId().equals(clazzId);
            }
            if (p.getCampusId() != null) {
                // 校区级 Agent 是否覆盖这个班，看班级是否属于该校区
                Clazz clazz = clazzMapper.selectById(clazzId);
                return clazz != null && p.getCampusId().equals(clazz.getCampusId());
            }
            return true; // 机构级 / 平台级
        }
        if (campusId != null) {
            if (p.getCampusId() != null) {
                return p.getCampusId().equals(campusId);
            }
            return true; // 机构级 / 平台级
        }
        return true;
    }

    private Campus requireCampus(Long id) {
        Campus campus = campusMapper.selectById(id);
        if (campus == null) {
            throw new BizException("校区不存在: " + id);
        }
        UserContext.checkOrgScope(campus.getOrgId());
        return campus;
    }

    /**
     * 该 Agent 的生效范围是否覆盖这名学生。
     *
     * <p>与 {@link #withinCallerScope} 同构，只是把「调用者的班级」换成「目标学生的班级」，
     * 复用同一套「粗的覆盖细的」规则。</p>
     */
    private boolean agentCoversStudent(AgentProfile agent, Long studentId) {
        if (agent.getClazzId() == null && agent.getCampusId() == null) {
            return true; // 机构级 / 平台级
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            return false;
        }
        if (agent.getClazzId() != null) {
            return agent.getClazzId().equals(student.getClazzId());
        }
        return agent.getCampusId().equals(student.getCampusId());
    }

    private Clazz requireClazz(Long id) {
        Clazz clazz = clazzMapper.selectById(id);
        if (clazz == null) {
            throw new BizException("班级不存在: " + id);
        }
        UserContext.checkOrgScope(clazz.getOrgId());
        return clazz;
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
        String campusName = null;
        if (p.getCampusId() != null) {
            Campus c = campusMapper.selectById(p.getCampusId());
            campusName = c == null ? null : c.getName();
        }
        String clazzName = null;
        if (p.getClazzId() != null) {
            Clazz c = clazzMapper.selectById(p.getClazzId());
            clazzName = c == null ? null : c.getName();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("orgId", p.getOrgId());
        m.put("campusId", p.getCampusId());
        m.put("clazzId", p.getClazzId());
        m.put("platformLevel", p.getOrgId() == null);
        m.put("scopeLevel", scopeLevel(p));
        m.put("scopeLabel", scopeLabel(p, campusName, clazzName));
        m.put("campusName", campusName);
        m.put("clazzName", clazzName);
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

    private static String scopeLevel(AgentProfile p) {
        if (p.getOrgId() == null) {
            return "PLATFORM";
        }
        if (p.getCampusId() == null) {
            return "ORG";
        }
        if (p.getClazzId() == null) {
            return "CAMPUS";
        }
        return "CLAZZ";
    }

    private static String scopeLabel(AgentProfile p, String campusName, String clazzName) {
        return switch (scopeLevel(p)) {
            case "PLATFORM" -> "全平台";
            case "ORG" -> "本机构全部校区";
            case "CAMPUS" -> "校区：" + (campusName == null ? "#" + p.getCampusId() : campusName);
            default -> "班级：" + (clazzName == null ? "#" + p.getClazzId() : clazzName)
                    + (campusName == null ? "" : "（" + campusName + "）");
        };
    }

    /** 新建 / 更新 Agent 编排配置。二期开放 systemPrompt 在线编辑。 */
    @Transactional
    public Map<String, Object> saveAgent(Long id, String code, Long orgId, Long campusId, Long clazzId,
                                         String name, String description,
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
        Scope scope = resolveScope(orgId, campusId, clazzId);

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
        p.setOrgId(scope.orgId());
        p.setCampusId(scope.campusId());
        p.setClazzId(scope.clazzId());
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
     * 上下文预演：在操作人的授权范围内读取指定学生的学习上下文，执行只读工具。
     *
     * <p>它回答的是「这个 Agent 以某个人的身份，<b>到底能拿到什么数据</b>」，
     * 模型对话由后续 Agent 运行时负责。
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
        if (agent.getOrgId() != null) {
            UserContext.checkOrgScope(agent.getOrgId());
        }
        // 目标学生的归属校验 —— 沿用既有边界，教师只能预演自己范围内的学生
        UserContext.checkStudentScope(studentId);
        studentService.requireStudent(studentId);
        // 生效范围校验：不能预演一个「不覆盖该学生」的 Agent，
        // 否则预演结果会让人误以为它在该班也可用
        if (!agentCoversStudent(agent, studentId)) {
            throw new ForbiddenException("该 Agent 的生效范围不包含这名学生，无法预演");
        }

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
            Object template = sg == null ? null : sg.get("graph");
            if (template instanceof com.kgquiz.quiz.entity.Graph g) {
                graphId = g.getId();
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
        out.put("identityNote", "预演使用当前操作人的授权范围，读取 studentId=" + studentId
                + " 的学习上下文；不切换学生登录身份，也不调用大模型。");
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
                Object tasks = t.get("tasks");
                long done = tasks instanceof List<?> list ? list.stream()
                        .filter(item -> item instanceof Map<?, ?> row && "DONE".equals(row.get("status"))).count() : 0;
                brief.put("pendingCount", t.get("pendingCount"));
                brief.put("doneCount", done);
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

    /**
     * 供其他模块复用：当前调用者这个角色能看到的 Agent。
     *
     * <p>三道过滤依次叠加：</p>
     * <ol>
     *   <li>{@code targetRole} 匹配且已启用（SQL 层）；</li>
     *   <li>机构边界 —— 非平台级 Agent 必须落在调用者的机构内；</li>
     *   <li><b>生效范围</b> —— 调用者只看到「在他所在的校区 / 班级里生效」的 Agent：
     *       班级级只对该班的教师与学生可见；校区级对该校区教师可见；
     *       机构级与平台级对全体本机构 / 全平台人员可见。</li>
     * </ol>
     *
     * <p>第 3 条是这轮加的。它让「给某个班单独配一个 Agent」在运行时真的成立，
     * 而不是只在管理台里躺着好看。</p>
     */
    public List<Map<String, Object>> agentsForRole(UserRole role) {
        CurrentUser user = UserContext.peek();
        List<AgentProfile> profiles = agentProfileMapper.selectList(
                new LambdaQueryWrapper<AgentProfile>()
                        .eq(AgentProfile::getTargetRole, role.name())
                        .eq(AgentProfile::getEnabled, 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : profiles) {
            if (p.getOrgId() != null && !UserContext.inScope(p.getOrgId())) {
                continue;
            }
            if (user != null && !withinCallerScope(p, user)) {
                continue;
            }
            out.add(toView(p));
        }
        return out;
    }

    /**
     * 该 Agent 是否在调用者的校区 / 班级范围内生效。
     *
     * <p>调用者没有校区归属（平台管理员、或未挂校区的机构管理员）时一律放行，
     * 因为他们的职责本来就是跨范围查看。</p>
     *
     * <p>学生账号的 {@code clazzIds} 恒为空（只有教师才带班），所以班级级判定要走
     * {@code studentId → student.clazz_id} 这条路；查不到归属时按「不属于任何班」处理，
     * 从而只看到机构级与平台级 Agent —— 这是收敛方向，宁可少看不可越界。</p>
     */
    private boolean withinCallerScope(AgentProfile p, CurrentUser user) {
        if (p.getClazzId() != null) {
            return callerClazzIds(user).contains(p.getClazzId());
        }
        if (p.getCampusId() != null) {
            if (user.campusId() == null) {
                return false;
            }
            return p.getCampusId().equals(user.campusId());
        }
        return true; // 机构级 / 平台级
    }

    /**
     * 调用者所属的全部班级：教师是带班集合（可能多个），学生是档案上那一个。
     * 之所以要算成集合而不是单个值，是因为教师带三个班时，
     * 挂在其中任意一个班上的 Agent 都该对他可见。
     */
    private Set<Long> callerClazzIds(CurrentUser user) {
        if (user.isStudent()) {
            if (user.studentId() == null) {
                return Set.of();
            }
            Student student = studentMapper.selectById(user.studentId());
            return student == null || student.getClazzId() == null
                    ? Set.of() : Set.of(student.getClazzId());
        }
        return new LinkedHashSet<>(user.clazzIds());
    }

    // ==================== 生效范围下钻（校区 / 班级总览）====================

    /**
     * 按校区 / 班级聚合的 Agent 生效总览。
     *
     * <p>给管理台回答两个问题：「这个校区 / 班里都启用了哪些 Agent」，
     * 以及「有没有漏配」。返回的 {@code directCount} 只统计<b>精确挂在该单元上</b>的 Agent，
     * {@code effectiveCount} 才算上从机构级、平台级继承下来的——
     * 否则一个都没单独配过的班级会显示成「0 个 Agent」，容易误判为没开。</p>
     */
    public Map<String, Object> scopeOverview() {
        Long scope = UserContext.scopeOrgId();
        List<AgentProfile> profiles = listProfiles(scope);

        List<Map<String, Object>> campusViews = new ArrayList<>();
        List<Map<String, Object>> clazzViews = new ArrayList<>();

        List<Campus> campuses = campusMapper.selectList(new LambdaQueryWrapper<Campus>()
                .eq(scope != null, Campus::getOrgId, scope)
                .orderByAsc(Campus::getId));
        List<Clazz> clazzes = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                .eq(scope != null, Clazz::getOrgId, scope)
                .orderByAsc(Clazz::getId));

        for (Campus campus : campuses) {
            List<Clazz> children = clazzes.stream()
                    .filter(c -> Objects.equals(c.getCampusId(), campus.getId()))
                    .toList();
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("id", campus.getId());
            cv.put("name", campus.getName());
            cv.put("orgId", campus.getOrgId());
            cv.put("clazzCount", children.size());
            cv.put("directCount", countExact(profiles, p -> p.getCampusId() != null
                    && p.getCampusId().equals(campus.getId())));
            cv.put("effectiveCount", countExact(profiles, p -> matchesScope(p, campus.getId(), null)));
            cv.put("directAgents", briefAgents(profiles, p -> p.getCampusId() != null
                    && p.getCampusId().equals(campus.getId())));
            campusViews.add(cv);
        }

        for (Clazz clazz : clazzes) {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("id", clazz.getId());
            cv.put("name", clazz.getName());
            cv.put("orgId", clazz.getOrgId());
            cv.put("campusId", clazz.getCampusId());
            cv.put("grade", clazz.getGrade());
            cv.put("subject", clazz.getSubject());
            cv.put("directCount", countExact(profiles, p -> p.getClazzId() != null
                    && p.getClazzId().equals(clazz.getId())));
            cv.put("effectiveCount", countExact(profiles, p -> matchesScope(p, null, clazz.getId())));
            cv.put("directAgents", briefAgents(profiles, p -> p.getClazzId() != null
                    && p.getClazzId().equals(clazz.getId())));
            clazzViews.add(cv);
        }
        clazzViews.sort(Comparator
                .comparing((Map<String, Object> m) -> (String) nz(m.get("grade")))
                .thenComparing(m -> String.valueOf(nz(m.get("name")))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgScope", scope);
        out.put("campuses", campusViews);
        out.put("clazzes", clazzViews);
        out.put("totalAgents", profiles.size());
        out.put("platformAgents", countExact(profiles, p -> p.getOrgId() == null));
        return out;
    }

    private int countExact(List<AgentProfile> profiles, java.util.function.Predicate<AgentProfile> cond) {
        int n = 0;
        for (AgentProfile p : profiles) {
            if (cond.test(p)) {
                n++;
            }
        }
        return n;
    }

    private List<Map<String, Object>> briefAgents(List<AgentProfile> profiles,
                                                 java.util.function.Predicate<AgentProfile> cond) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentProfile p : profiles) {
            if (!cond.test(p)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("targetRole", p.getTargetRole());
            m.put("enabled", p.getEnabled());
            out.add(m);
        }
        return out;
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
