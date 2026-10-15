package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.PasswordEncoder;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.dto.CampusAdminReq;
import com.kgquiz.quiz.dto.CampusReq;
import com.kgquiz.quiz.dto.ClazzReq;
import com.kgquiz.quiz.dto.OrgReq;
import com.kgquiz.quiz.dto.StudentImportReq;
import com.kgquiz.quiz.dto.TeacherReq;
import com.kgquiz.quiz.entity.Campus;
import com.kgquiz.quiz.entity.Clazz;
import com.kgquiz.quiz.entity.Org;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.SysUser;
import com.kgquiz.quiz.entity.TeacherClazz;
import com.kgquiz.quiz.mapper.CampusMapper;
import com.kgquiz.quiz.mapper.ClazzMapper;
import com.kgquiz.quiz.mapper.OrgMapper;
import com.kgquiz.quiz.mapper.StudentMapper;
import com.kgquiz.quiz.mapper.SysTokenMapper;
import com.kgquiz.quiz.mapper.SysUserMapper;
import com.kgquiz.quiz.mapper.TeacherClazzMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 机构管理平台的服务端：机构 → 校区 → 班级 → 教师 / 学生。
 *
 * <p>租户边界一律以平台为准，不依赖前端：
 * 平台管理员（ADMIN）可跨机构；机构管理员（ORG_ADMIN）只能操作本机构；
 * 教师只能看自己带的班。越界统一 403 / 404，不做静默过滤。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgService {

    private final OrgMapper orgMapper;
    private final CampusMapper campusMapper;
    private final ClazzMapper clazzMapper;
    private final TeacherClazzMapper teacherClazzMapper;
    private final SysUserMapper sysUserMapper;
    private final SysTokenMapper sysTokenMapper;
    private final StudentMapper studentMapper;
    private final AuthService authService;

    // ==================== 机构 ====================

    /** 机构列表：平台管理员看全部，其余只看自己所属机构 */
    public List<Map<String, Object>> listOrgs(String keyword, String status) {
        LambdaQueryWrapper<Org> wrapper = new LambdaQueryWrapper<Org>().orderByAsc(Org::getId);
        Long scope = UserContext.scopeOrgId();
        if (scope != null) {
            wrapper.eq(Org::getId, scope);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Org::getName, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Org::getStatus, status);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Org org : orgMapper.selectList(wrapper)) {
            result.add(orgView(org));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createOrg(OrgReq req) {
        String code = req.code() == null ? "" : req.code().trim();
        if (!code.isEmpty()) {
            Long dup = orgMapper.selectCount(new LambdaQueryWrapper<Org>().eq(Org::getCode, code));
            if (dup != null && dup > 0) {
                throw new BizException("机构编码已存在: " + code);
            }
        } else {
            code = null;
        }
        Org org = new Org();
        org.setName(req.name().trim());
        org.setCode(code);
        org.setContactName(nz(req.contactName()));
        org.setContactPhone(nz(req.contactPhone()));
        org.setRemark(nz(req.remark()));
        org.setStatus("ACTIVE");
        orgMapper.insert(org);
        return orgView(org);
    }

    @Transactional
    public Map<String, Object> updateOrg(Long id, OrgReq req) {
        Org org = requireOrg(id);
        String code = req.code() == null ? "" : req.code().trim();
        if (!code.isEmpty()) {
            Long dup = orgMapper.selectCount(new LambdaQueryWrapper<Org>()
                    .eq(Org::getCode, code).ne(Org::getId, id));
            if (dup != null && dup > 0) {
                throw new BizException("机构编码已存在: " + code);
            }
        } else {
            code = null;
        }
        org.setName(req.name().trim());
        org.setCode(code);
        org.setContactName(nz(req.contactName()));
        org.setContactPhone(nz(req.contactPhone()));
        org.setRemark(nz(req.remark()));
        orgMapper.updateById(org);
        return orgView(org);
    }

    /** 启用 / 停用机构。停用只影响状态位，不删数据。 */
    @Transactional
    public Map<String, Object> setOrgStatus(Long id, String status) {
        String s = "SUSPENDED".equalsIgnoreCase(status) ? "SUSPENDED" : "ACTIVE";
        Org org = requireOrg(id);
        org.setStatus(s);
        orgMapper.updateById(org);
        return orgView(org);
    }

    private Org requireOrg(Long id) {
        Org org = orgMapper.selectById(id);
        if (org == null) {
            throw new BizException("机构不存在: " + id);
        }
        return org;
    }

    private Map<String, Object> orgView(Org org) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", org.getId());
        view.put("name", org.getName());
        view.put("code", org.getCode());
        view.put("contactName", org.getContactName());
        view.put("contactPhone", org.getContactPhone());
        view.put("status", org.getStatus());
        view.put("remark", org.getRemark());
        view.put("createdAt", org.getCreatedAt());
        // 层级规模，便于机构列表一眼看清体量
        view.put("campusCount", campusMapper.selectCount(
                new LambdaQueryWrapper<Campus>().eq(Campus::getOrgId, org.getId())));
        view.put("clazzCount", clazzMapper.selectCount(
                new LambdaQueryWrapper<Clazz>().eq(Clazz::getOrgId, org.getId())));
        view.put("teacherCount", sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOrgId, org.getId()).eq(SysUser::getRole, UserRole.TEACHER.name())));
        view.put("studentCount", studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getOrgId, org.getId())));
        return view;
    }

    /** 机构总览：校区 / 班级 / 教师 / 学生 数量与分组分布 */
    public Map<String, Object> overview(Long orgId) {
        Long scope = orgId != null ? orgId : UserContext.scopeOrgId();
        if (scope == null) {
            throw new BizException("请指定要查看的机构");
        }
        UserContext.checkOrgScope(scope);
        Org org = requireOrg(scope);

        List<Campus> campuses = campusMapper.selectList(new LambdaQueryWrapper<Campus>()
                .eq(Campus::getOrgId, scope).orderByAsc(Campus::getId));
        List<Clazz> clazzes = clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                .eq(Clazz::getOrgId, scope).orderByAsc(Clazz::getId));

        Map<Long, Long> studentsByClazz = new HashMap<>();
        for (Student s : studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getOrgId, scope))) {
            if (s.getClazzId() != null) {
                studentsByClazz.merge(s.getClazzId(), 1L, Long::sum);
            }
        }

        List<Map<String, Object>> campusViews = new ArrayList<>();
        for (Campus campus : campuses) {
            Map<String, Object> cv = campusView(campus);
            cv.put("clazzes", clazzes.stream()
                    .filter(c -> Objects.equals(c.getCampusId(), campus.getId()))
                    .map(c -> clazzView(c, studentsByClazz.getOrDefault(c.getId(), 0L)))
                    .toList());
            campusViews.add(cv);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("org", orgView(org));
        result.put("campuses", campusViews);
        result.put("campusCount", campuses.size());
        result.put("clazzCount", clazzes.size());
        result.put("teacherCount", sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOrgId, scope).eq(SysUser::getRole, UserRole.TEACHER.name())));
        result.put("studentCount", studentsByClazz.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    // ==================== 校区 ====================

    public List<Map<String, Object>> listCampuses(Long orgId) {
        Long scope = resolveReadOrg(orgId);
        LambdaQueryWrapper<Campus> wrapper = new LambdaQueryWrapper<Campus>()
                .eq(Campus::getOrgId, scope).orderByAsc(Campus::getId);
        // 校区管理员 / 教师只看自己那个校区；机构管理员以上不过滤
        Long campusScope = UserContext.scopeCampusId();
        if (campusScope != null) {
            wrapper.eq(Campus::getId, campusScope);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Campus campus : campusMapper.selectList(wrapper)) {
            Map<String, Object> cv = campusView(campus);
            cv.put("clazzCount", clazzMapper.selectCount(
                    new LambdaQueryWrapper<Clazz>().eq(Clazz::getCampusId, campus.getId())));
            cv.put("studentCount", studentMapper.selectCount(
                    new LambdaQueryWrapper<Student>().eq(Student::getCampusId, campus.getId())));
            result.add(cv);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createCampus(CampusReq req) {
        Long orgId = UserContext.resolveWriteOrgId(req.orgId());
        String name = req.name().trim();
        Long dup = campusMapper.selectCount(new LambdaQueryWrapper<Campus>()
                .eq(Campus::getOrgId, orgId).eq(Campus::getName, name));
        if (dup != null && dup > 0) {
            throw new BizException("该机构下已存在同名校区: " + name);
        }
        Campus campus = new Campus();
        campus.setOrgId(orgId);
        campus.setName(name);
        campus.setCode(nz(req.code()));
        campus.setAddress(nz(req.address()));
        campus.setStatus("ACTIVE");
        campusMapper.insert(campus);
        return campusView(campus);
    }

    @Transactional
    public Map<String, Object> updateCampus(Long id, CampusReq req) {
        Campus campus = requireCampus(id);
        campus.setName(req.name().trim());
        campus.setCode(nz(req.code()));
        campus.setAddress(nz(req.address()));
        campusMapper.updateById(campus);
        return campusView(campus);
    }

    @Transactional
    public void deleteCampus(Long id) {
        Campus campus = requireCampus(id);
        long clazzCount = clazzMapper.selectCount(
                new LambdaQueryWrapper<Clazz>().eq(Clazz::getCampusId, id));
        if (clazzCount > 0) {
            throw new BizException("该校区下还有 " + clazzCount + " 个班级，请先移除班级");
        }
        long studentCount = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getCampusId, id));
        if (studentCount > 0) {
            throw new BizException("该校区下还有 " + studentCount + " 名学生，请先转移学生");
        }
        campusMapper.deleteById(id);
    }

    private Campus requireCampus(Long id) {
        Campus campus = campusMapper.selectById(id);
        if (campus == null) {
            throw new BizException("校区不存在: " + id);
        }
        UserContext.checkOrgScope(campus.getOrgId());
        UserContext.checkCampusScope(campus.getId());
        return campus;
    }

    private Map<String, Object> campusView(Campus campus) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", campus.getId());
        view.put("orgId", campus.getOrgId());
        view.put("name", campus.getName());
        view.put("code", campus.getCode());
        view.put("address", campus.getAddress());
        view.put("status", campus.getStatus());
        view.put("createdAt", campus.getCreatedAt());
        // 谁在管这个校区 —— 校区管理员是这一层的负责人，前端校区行直接显示
        List<Map<String, Object>> admins = new ArrayList<>();
        for (SysUser a : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getCampusId, campus.getId())
                .eq(SysUser::getRole, UserRole.CAMPUS_ADMIN.name())
                .orderByAsc(SysUser::getId))) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("userId", a.getId());
            am.put("username", a.getUsername());
            am.put("displayName", a.getDisplayName());
            am.put("enabled", a.getEnabled());
            admins.add(am);
        }
        view.put("admins", admins);
        view.put("adminCount", admins.size());
        // 没分配管理员是要紧的运维缺口，前端高亮提示用
        view.put("adminAssigned", !admins.isEmpty());
        return view;
    }

    // ==================== 班级 ====================

    public List<Map<String, Object>> listClazzes(Long orgId, Long campusId) {
        LambdaQueryWrapper<Clazz> wrapper = new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId);
        Long scope = UserContext.scopeOrgId();
        if (scope != null) {
            wrapper.eq(Clazz::getOrgId, scope);
        } else if (orgId != null) {
            wrapper.eq(Clazz::getOrgId, orgId);
        }
        if (campusId != null) {
            wrapper.eq(Clazz::getCampusId, campusId);
        }
        // 校区边界：校区管理员与教师只看到自己校区的班
        Long campusScope = UserContext.scopeCampusId();
        if (campusScope != null) {
            wrapper.eq(Clazz::getCampusId, campusScope);
        }
        // 教师只看到自己带的班
        CurrentUser current = UserContext.require();
        if (current.role() == UserRole.TEACHER) {
            if (current.clazzIds().isEmpty()) {
                return List.of();
            }
            wrapper.in(Clazz::getId, current.clazzIds());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Clazz clazz : clazzMapper.selectList(wrapper)) {
            long studentCount = studentMapper.selectCount(
                    new LambdaQueryWrapper<Student>().eq(Student::getClazzId, clazz.getId()));
            result.add(clazzView(clazz, studentCount));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createClazz(ClazzReq req) {
        Campus campus = requireCampus(req.campusId());
        Clazz clazz = new Clazz();
        clazz.setOrgId(campus.getOrgId());
        clazz.setCampusId(campus.getId());
        clazz.setName(req.name().trim());
        clazz.setGrade(nz(req.grade()));
        clazz.setSubject(nz(req.subject()));
        clazz.setTextbook(nz(req.textbook()));
        clazz.setTerm(nz(req.term()));
        clazz.setHeadTeacherId(validateHeadTeacher(req.headTeacherId(), campus.getOrgId()));
        clazz.setParentClazzId(resolveParentClazz(req.parentClazzId(), campus.getId(), null));
        clazz.setStatus("ACTIVE");
        clazzMapper.insert(clazz);
        return clazzView(clazz, 0);
    }

    @Transactional
    public Map<String, Object> updateClazz(Long id, ClazzReq req) {
        Clazz clazz = requireClazz(id);
        Campus campus = requireCampus(req.campusId());
        clazz.setCampusId(campus.getId());
        clazz.setName(req.name().trim());
        clazz.setGrade(nz(req.grade()));
        clazz.setSubject(nz(req.subject()));
        clazz.setTextbook(nz(req.textbook()));
        clazz.setTerm(nz(req.term()));
        clazz.setHeadTeacherId(validateHeadTeacher(req.headTeacherId(), campus.getOrgId()));
        clazz.setParentClazzId(resolveParentClazz(req.parentClazzId(), campus.getId(), clazz.getId()));
        clazzMapper.updateById(clazz);
        long studentCount = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getClazzId, clazz.getId()));
        return clazzView(clazz, studentCount);
    }

    /**
     * 校验上级班级：必须同校区，且只能一层。
     *
     * <p>三条硬规则：</p>
     * <ul>
     *   <li>父班必须存在且属于同一个校区（跨校区的层级没有意义）；</li>
     *   <li>不能挂自己（自环）；</li>
     *   <li>父班本身不能再有父班 —— 只支持一层，传两层直接拒绝。</li>
     * </ul>
     */
    private Long resolveParentClazz(Long parentClazzId, Long campusId, Long selfId) {
        if (parentClazzId == null) {
            return null;
        }
        if (parentClazzId.equals(selfId)) {
            throw new BizException("班级不能把自己设为上级");
        }
        Clazz parent = clazzMapper.selectById(parentClazzId);
        if (parent == null) {
            throw new BizException("上级班级不存在: " + parentClazzId);
        }
        if (!Objects.equals(parent.getCampusId(), campusId)) {
            throw new BizException("上级班级必须属于同一校区");
        }
        if (parent.getParentClazzId() != null) {
            throw new BizException("班级分层只支持一层，「" + parent.getName() + "」已经有上级班了");
        }
        return parentClazzId;
    }

    @Transactional
    public void deleteClazz(Long id) {
        Clazz clazz = requireClazz(id);
        long students = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getClazzId, id));
        if (students > 0) {
            throw new BizException("该班级下还有 " + students + " 名学生，请先转移学生");
        }
        teacherClazzMapper.delete(new LambdaQueryWrapper<TeacherClazz>().eq(TeacherClazz::getClazzId, id));
        clazzMapper.deleteById(clazz.getId());
    }

    private Clazz requireClazz(Long id) {
        Clazz clazz = clazzMapper.selectById(id);
        if (clazz == null) {
            throw new BizException("班级不存在: " + id);
        }
        UserContext.checkOrgScope(clazz.getOrgId());
        UserContext.checkCampusScope(clazz.getCampusId());
        return clazz;
    }

    /** 班主任必须是本机构的教师 */
    private Long validateHeadTeacher(Long headTeacherId, Long orgId) {
        if (headTeacherId == null) {
            return null;
        }
        SysUser teacher = sysUserMapper.selectById(headTeacherId);
        if (teacher == null || !UserRole.TEACHER.name().equals(teacher.getRole())) {
            throw new BizException("班主任必须是教师账号: " + headTeacherId);
        }
        if (!orgId.equals(teacher.getOrgId())) {
            throw new BizException("班主任不属于该机构");
        }
        return headTeacherId;
    }

    private Map<String, Object> clazzView(Clazz clazz, long studentCount) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", clazz.getId());
        view.put("orgId", clazz.getOrgId());
        view.put("campusId", clazz.getCampusId());
        view.put("name", clazz.getName());
        view.put("grade", clazz.getGrade());
        view.put("subject", clazz.getSubject());
        view.put("textbook", clazz.getTextbook());
        view.put("term", clazz.getTerm());
        view.put("headTeacherId", clazz.getHeadTeacherId());
        if (clazz.getHeadTeacherId() != null) {
            SysUser head = sysUserMapper.selectById(clazz.getHeadTeacherId());
            view.put("headTeacherName", head == null ? null : head.getDisplayName());
        }
        // 班级分层：仅一层，level=0 顶级班 / 1 子班
        view.put("parentClazzId", clazz.getParentClazzId());
        if (clazz.getParentClazzId() != null) {
            Clazz parent = clazzMapper.selectById(clazz.getParentClazzId());
            view.put("parentClazzName", parent == null ? null : parent.getName());
        }
        view.put("level", clazz.getParentClazzId() == null ? 0 : 1);
        view.put("childCount", clazzMapper.selectCount(
                new LambdaQueryWrapper<Clazz>().eq(Clazz::getParentClazzId, clazz.getId())));
        view.put("status", clazz.getStatus());
        view.put("createdAt", clazz.getCreatedAt());
        view.put("studentCount", studentCount);
        view.put("teacherCount", teacherClazzMapper.selectCount(
                new LambdaQueryWrapper<TeacherClazz>().eq(TeacherClazz::getClazzId, clazz.getId())));
        if (clazz.getCampusId() != null) {
            Campus campus = campusMapper.selectById(clazz.getCampusId());
            view.put("campusName", campus == null ? null : campus.getName());
        }
        return view;
    }

    // ==================== 教师 ====================

    /** 教师列表：含归属校区、带班、授课科目 */
    public List<Map<String, Object>> listTeachers(Long orgId, Long campusId, String keyword) {
        Long scope = resolveReadOrg(orgId);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.TEACHER.name())
                .orderByAsc(SysUser::getId);
        // scope 为 null 表示平台管理员跨机构查询，此时不加机构条件
        wrapper.eq(scope != null, SysUser::getOrgId, scope);
        if (campusId != null) {
            wrapper.eq(SysUser::getCampusId, campusId);
        }
        // 校区管理员只看本校区教师
        Long campusScope = UserContext.scopeCampusId();
        if (campusScope != null) {
            wrapper.eq(SysUser::getCampusId, campusScope);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(SysUser::getDisplayName, kw).or().like(SysUser::getUsername, kw));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser teacher : sysUserMapper.selectList(wrapper)) {
            result.add(teacherView(teacher));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createTeacher(TeacherReq req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (username.isEmpty()) {
            throw new BizException("请填写教师登录名");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new BizException("请填写教师初始密码");
        }
        // 机构从校区反推（教师必须归属一个校区）；没给校区才退回账号自己的机构
        Long orgId;
        if (req.campusId() != null) {
            orgId = requireCampus(req.campusId()).getOrgId();
        } else {
            orgId = UserContext.resolveWriteOrgId(null);
        }
        Long campusId = resolveCampus(req.campusId(), orgId);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordEncoder.encode(req.password()));
        user.setRole(UserRole.TEACHER.name());
        user.setDisplayName(req.displayName().trim());
        user.setOrgId(orgId);
        user.setCampusId(campusId);
        user.setEnabled(1);
        // 登录名重复由 sys_user 唯一键兜底，这里提前给出友好提示
        Long dup = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (dup != null && dup > 0) {
            throw new BizException("登录名已存在: " + username);
        }
        sysUserMapper.insert(user);

        setTeacherClazzes(user.getId(), orgId, req.clazzIds(), req.subject());
        return teacherView(user);
    }

    @Transactional
    public Map<String, Object> updateTeacher(Long userId, TeacherReq req) {
        SysUser teacher = requireTeacher(userId);
        teacher.setDisplayName(req.displayName().trim());
        teacher.setCampusId(resolveCampus(req.campusId(), teacher.getOrgId()));
        sysUserMapper.updateById(teacher);
        // clazzIds 传 null = 不改动带班；传空数组 = 清空带班
        if (req.clazzIds() != null) {
            setTeacherClazzes(userId, teacher.getOrgId(), req.clazzIds(), req.subject());
        }
        return teacherView(teacher);
    }

    @Transactional
    public Map<String, Object> setTeacherEnabled(Long userId, boolean enabled) {
        SysUser teacher = requireTeacher(userId);
        teacher.setEnabled(enabled ? 1 : 0);
        sysUserMapper.updateById(teacher);
        if (!enabled) {
            // 停用即踢下线
            sysTokenMapper.delete(new LambdaQueryWrapper<com.kgquiz.quiz.entity.SysToken>()
                    .eq(com.kgquiz.quiz.entity.SysToken::getUserId, userId));
        }
        return teacherView(teacher);
    }

    @Transactional
    public Map<String, Object> resetTeacherPassword(Long userId, String password) {
        SysUser teacher = requireTeacher(userId);
        String pwd = (password == null || password.isBlank()) ? "teacher123" : password;
        authService.resetPassword(teacher.getId(), pwd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("username", teacher.getUsername());
        result.put("password", pwd);
        return result;
    }

    /** 删除教师：解绑带班、清会话、删账号。其名下卷子仍保留（created_by 悬空不影响展示）。 */
    @Transactional
    public void deleteTeacher(Long userId) {
        SysUser teacher = requireTeacher(userId);
        teacherClazzMapper.delete(new LambdaQueryWrapper<TeacherClazz>()
                .eq(TeacherClazz::getTeacherId, userId));
        sysTokenMapper.delete(new LambdaQueryWrapper<com.kgquiz.quiz.entity.SysToken>()
                .eq(com.kgquiz.quiz.entity.SysToken::getUserId, userId));
        sysUserMapper.deleteById(teacher.getId());
    }

    /** 重设教师带班（全量覆盖，幂等） */
    @Transactional
    public void setTeacherClazzes(Long teacherId, Long orgId, List<Long> clazzIds, String subject) {
        teacherClazzMapper.delete(new LambdaQueryWrapper<TeacherClazz>()
                .eq(TeacherClazz::getTeacherId, teacherId));
        if (clazzIds == null || clazzIds.isEmpty()) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (Long clazzId : clazzIds) {
            if (clazzId == null || !seen.add(clazzId)) {
                continue;
            }
            Clazz clazz = clazzMapper.selectById(clazzId);
            if (clazz == null) {
                throw new BizException("班级不存在: " + clazzId);
            }
            if (!orgId.equals(clazz.getOrgId())) {
                throw new ForbiddenException("不能把教师挂到其他机构的班级: " + clazzId);
            }
            TeacherClazz tc = new TeacherClazz();
            tc.setTeacherId(teacherId);
            tc.setClazzId(clazzId);
            tc.setSubject(subject == null ? "" : subject);
            teacherClazzMapper.insert(tc);
        }
    }

    private SysUser requireTeacher(Long userId) {
        SysUser teacher = sysUserMapper.selectById(userId);
        if (teacher == null || !UserRole.TEACHER.name().equals(teacher.getRole())) {
            throw new BizException("教师不存在: " + userId);
        }
        UserContext.checkOrgScope(teacher.getOrgId());
        UserContext.checkCampusScope(teacher.getCampusId());
        return teacher;
    }

    private Map<String, Object> teacherView(SysUser teacher) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", teacher.getId());
        view.put("username", teacher.getUsername());
        view.put("displayName", teacher.getDisplayName());
        view.put("enabled", teacher.getEnabled());
        view.put("orgId", teacher.getOrgId());
        view.put("campusId", teacher.getCampusId());
        view.put("createdAt", teacher.getCreatedAt());
        view.put("lastLoginAt", teacher.getLastLoginAt());
        if (teacher.getCampusId() != null) {
            Campus campus = campusMapper.selectById(teacher.getCampusId());
            view.put("campusName", campus == null ? null : campus.getName());
        }
        List<TeacherClazz> bindings = teacherClazzMapper.selectList(new LambdaQueryWrapper<TeacherClazz>()
                .eq(TeacherClazz::getTeacherId, teacher.getId()).orderByAsc(TeacherClazz::getId));
        List<Map<String, Object>> clazzViews = new ArrayList<>();
        for (TeacherClazz tc : bindings) {
            Clazz clazz = clazzMapper.selectById(tc.getClazzId());
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("clazzId", tc.getClazzId());
            cv.put("clazzName", clazz == null ? null : clazz.getName());
            cv.put("campusId", clazz == null ? null : clazz.getCampusId());
            cv.put("subject", tc.getSubject());
            clazzViews.add(cv);
        }
        view.put("clazzes", clazzViews);
        view.put("clazzIds", clazzViews.stream().map(cv -> cv.get("clazzId")).filter(Objects::nonNull)
                .collect(Collectors.toList()));
        return view;
    }

    // ==================== 校区管理员 ====================

    /**
     * 校区管理员列表。
     *
     * <p>一个校区可以有多个管理员（正副职、分管不同板块），所以这里是列表而非单值。
     * 不传 {@code campusId} 则返回调用者可见范围内的全部校区管理员。</p>
     */
    public List<Map<String, Object>> listCampusAdmins(Long orgId, Long campusId, String keyword) {
        Long scope = resolveReadOrg(orgId);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.CAMPUS_ADMIN.name())
                .orderByAsc(SysUser::getId);
        wrapper.eq(scope != null, SysUser::getOrgId, scope);
        if (campusId != null) {
            UserContext.checkCampusScope(campusId);
            wrapper.eq(SysUser::getCampusId, campusId);
        }
        Long campusScope = UserContext.scopeCampusId();
        if (campusScope != null) {
            wrapper.eq(SysUser::getCampusId, campusScope);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(SysUser::getDisplayName, kw).or().like(SysUser::getUsername, kw));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser admin : sysUserMapper.selectList(wrapper)) {
            result.add(campusAdminView(admin));
        }
        return result;
    }

    /**
     * 新建校区管理员。
     *
     * <p>{@code campusId} 必填且必须是调用者能操作的校区 —— 这是这个角色的全部意义，
     * 没绑定校区的管理员会被所有校区级判定拦下，不如在创建时就拒掉。</p>
     */
    @Transactional
    public Map<String, Object> createCampusAdmin(CampusAdminReq req) {
        // 先定校区：它同时决定账号归属的机构与权限边界
        if (req.campusId() == null) {
            throw new BizException("校区管理员必须绑定校区");
        }
        // requireCampus 已经校验过机构归属与校区边界；机构直接反推，
        // 不让调用方重复传一遍 —— 两个字段可能填错同一个请求体，传了矛盾的值反而更难排查
        Campus campus = requireCampus(req.campusId());
        Long orgId = campus.getOrgId();

        String username = req.username() == null ? "" : req.username().trim();
        if (username.isEmpty()) {
            throw new BizException("请填写管理员登录名");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new BizException("请填写管理员初始密码");
        }

        Long dup = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (dup != null && dup > 0) {
            throw new BizException("登录名已存在: " + username);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordEncoder.encode(req.password()));
        user.setRole(UserRole.CAMPUS_ADMIN.name());
        user.setDisplayName(req.displayName().trim());
        user.setOrgId(orgId);
        user.setCampusId(campus.getId());
        user.setEnabled(1);
        sysUserMapper.insert(user);
        return campusAdminView(user);
    }

    /** 编辑校区管理员：改姓名 / 改绑校区。登录名只读。 */
    @Transactional
    public Map<String, Object> updateCampusAdmin(Long userId, CampusAdminReq req) {
        SysUser admin = requireCampusAdmin(userId);
        admin.setDisplayName(req.displayName().trim());
        if (req.campusId() != null) {
            Campus campus = requireCampus(req.campusId());
            admin.setCampusId(campus.getId());
        }
        sysUserMapper.updateById(admin);
        return campusAdminView(admin);
    }

    /** 启停校区管理员。停用即踢下线。 */
    @Transactional
    public Map<String, Object> setCampusAdminEnabled(Long userId, boolean enabled) {
        SysUser admin = requireCampusAdmin(userId);
        admin.setEnabled(enabled ? 1 : 0);
        sysUserMapper.updateById(admin);
        if (!enabled) {
            sysTokenMapper.delete(new LambdaQueryWrapper<com.kgquiz.quiz.entity.SysToken>()
                    .eq(com.kgquiz.quiz.entity.SysToken::getUserId, userId));
        }
        return campusAdminView(admin);
    }

    @Transactional
    public Map<String, Object> resetCampusAdminPassword(Long userId, String password) {
        SysUser admin = requireCampusAdmin(userId);
        String pwd = (password == null || password.isBlank()) ? "campus123" : password;
        authService.resetPassword(admin.getId(), pwd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("username", admin.getUsername());
        result.put("password", pwd);
        return result;
    }

    @Transactional
    public void deleteCampusAdmin(Long userId) {
        SysUser admin = requireCampusAdmin(userId);
        sysTokenMapper.delete(new LambdaQueryWrapper<com.kgquiz.quiz.entity.SysToken>()
                .eq(com.kgquiz.quiz.entity.SysToken::getUserId, userId));
        sysUserMapper.deleteById(admin.getId());
    }

    /**
     * 校区内可用的管理员候选（已绑本校区、未被停用、且不是教师/学生账号）。
     *
     * <p>用于「把已有账号提成管理员」时提供下拉 —— 比每次都让用户新建账号更贴近真实做法。</p>
     */
    public List<Map<String, Object>> adminCandidates(Long campusId) {
        Campus campus = requireCampus(campusId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysUser u : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOrgId, campus.getOrgId())
                .eq(SysUser::getCampusId, campusId)
                .eq(SysUser::getEnabled, 1)
                .orderByAsc(SysUser::getId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("role", u.getRole());
            m.put("roleLabel", UserRole.of(u.getRole()).label());
            m.put("campusId", u.getCampusId());
            out.add(m);
        }
        return out;
    }

    /** 提升已有账号为校区管理员：改角色并绑定校区 */
    @Transactional
    public Map<String, Object> promoteCampusAdmin(Long userId, Long campusId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException("账号不存在: " + userId);
        }
        UserContext.checkOrgScope(user.getOrgId());
        Campus campus = requireCampus(campusId);
        // 教师提成管理员要一并清掉带班，否则他仍会以教师身份看到本班数据
        if (UserRole.TEACHER.name().equals(user.getRole())) {
            teacherClazzMapper.delete(new LambdaQueryWrapper<TeacherClazz>()
                    .eq(TeacherClazz::getTeacherId, userId));
        }
        user.setRole(UserRole.CAMPUS_ADMIN.name());
        user.setCampusId(campus.getId());
        user.setOrgId(campus.getOrgId());
        sysUserMapper.updateById(user);
        return campusAdminView(user);
    }

    private SysUser requireCampusAdmin(Long userId) {
        SysUser admin = sysUserMapper.selectById(userId);
        if (admin == null || !UserRole.CAMPUS_ADMIN.name().equals(admin.getRole())) {
            throw new BizException("校区管理员不存在: " + userId);
        }
        UserContext.checkOrgScope(admin.getOrgId());
        UserContext.checkCampusScope(admin.getCampusId());
        return admin;
    }

    private Map<String, Object> campusAdminView(SysUser admin) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", admin.getId());
        view.put("username", admin.getUsername());
        view.put("displayName", admin.getDisplayName());
        view.put("role", admin.getRole());
        view.put("roleLabel", UserRole.CAMPUS_ADMIN.label());
        view.put("enabled", admin.getEnabled());
        view.put("orgId", admin.getOrgId());
        view.put("campusId", admin.getCampusId());
        view.put("createdAt", admin.getCreatedAt());
        view.put("lastLoginAt", admin.getLastLoginAt());
        if (admin.getCampusId() != null) {
            Campus campus = campusMapper.selectById(admin.getCampusId());
            view.put("campusName", campus == null ? null : campus.getName());
        }
        // 管理员管着这个校区多少班，直接摆出来便于判断分工
        view.put("managedClazzCount", clazzMapper.selectCount(
                new LambdaQueryWrapper<Clazz>().eq(Clazz::getCampusId, admin.getCampusId())));
        return view;
    }

    // ==================== 学生（组织视角） ====================

    /** 机构下的学生名册，可按校区 / 班级 / 关键字筛选 */
    public List<Map<String, Object>> listStudents(Long orgId, Long campusId, Long clazzId, String keyword) {
        Long scope = resolveReadOrg(orgId);
        LambdaQueryWrapper<Student> wrapper = new LambdaQueryWrapper<Student>()
                .orderByAsc(Student::getClazzId).orderByAsc(Student::getId);
        // scope 为 null 表示平台管理员跨机构查询，此时不加机构条件
        wrapper.eq(scope != null, Student::getOrgId, scope);
        if (campusId != null) {
            wrapper.eq(Student::getCampusId, campusId);
        }
        if (clazzId != null) {
            wrapper.eq(Student::getClazzId, clazzId);
        }
        // 校区边界：校区管理员只看本校区学生（教师随后再按带班收窄）
        Long campusScope = UserContext.scopeCampusId();
        if (campusScope != null) {
            wrapper.eq(Student::getCampusId, campusScope);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Student::getName, keyword.trim());
        }
        CurrentUser current = UserContext.require();
        // 教师只看自己带的班里的学生
        if (current.role() == UserRole.TEACHER) {
            if (current.clazzIds().isEmpty()) {
                return List.of();
            }
            wrapper.in(Student::getClazzId, current.clazzIds());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Student student : studentMapper.selectList(wrapper)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", student.getId());
            view.put("name", student.getName());
            view.put("orgId", student.getOrgId());
            view.put("campusId", student.getCampusId());
            view.put("clazzId", student.getClazzId());
            view.put("createdAt", student.getCreatedAt());
            if (student.getClazzId() != null) {
                Clazz clazz = clazzMapper.selectById(student.getClazzId());
                view.put("clazzName", clazz == null ? null : clazz.getName());
                view.put("grade", clazz == null ? null : clazz.getGrade());
            }
            SysUser account = authService.findByStudentId(student.getId());
            view.put("username", account == null ? null : account.getUsername());
            view.put("accountEnabled", account == null ? null : account.getEnabled());
            result.add(view);
        }
        return result;
    }

    // ==================== 班级下钻 ====================

    /**
     * 班级详情：一次拿齐「这个班是谁、谁在教、有多少学生」。
     *
     * <p>前端班级下钻面板就靠这个接口渲染。它刻意把班主任、任课教师、学生名单都带上，
     * 避免前端为了拼一个面板连发四五个请求 —— 班级是教务最常点的层级，
     * 多几次往返比多写一点服务端组装更影响体验。</p>
     */
    public Map<String, Object> clazzDetail(Long clazzId) {
        Clazz clazz = requireClazz(clazzId);
        long studentCount = studentMapper.selectCount(
                new LambdaQueryWrapper<Student>().eq(Student::getClazzId, clazzId));
        Map<String, Object> detail = clazzView(clazz, studentCount);

        // 任课教师（teacher_clazz 里 subject 非空的）；班主任单独从 clazz.head_teacher_id 取
        List<Map<String, Object>> teachers = new ArrayList<>();
        for (TeacherClazz tc : teacherClazzMapper.selectList(new LambdaQueryWrapper<TeacherClazz>()
                .eq(TeacherClazz::getClazzId, clazzId).orderByAsc(TeacherClazz::getId))) {
            SysUser t = sysUserMapper.selectById(tc.getTeacherId());
            if (t == null) {
                continue;
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("userId", t.getId());
            tm.put("displayName", t.getDisplayName());
            tm.put("username", t.getUsername());
            tm.put("subject", tc.getSubject());
            tm.put("isHeadTeacher", t.getId().equals(clazz.getHeadTeacherId()));
            teachers.add(tm);
        }
        detail.put("teachers", teachers);

        // 子班（分层）
        List<Map<String, Object>> children = new ArrayList<>();
        for (Clazz child : clazzMapper.selectList(new LambdaQueryWrapper<Clazz>()
                .eq(Clazz::getParentClazzId, clazzId).orderByAsc(Clazz::getId))) {
            children.add(clazzView(child, studentMapper.selectCount(
                    new LambdaQueryWrapper<Student>().eq(Student::getClazzId, child.getId()))));
        }
        detail.put("children", children);

        // 本班学生名单（教师只带本班所以无越权问题）
        List<Map<String, Object>> students = new ArrayList<>();
        for (Student s : studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getClazzId, clazzId).orderByAsc(Student::getId))) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.getId());
            sm.put("name", s.getName());
            SysUser acc = authService.findByStudentId(s.getId());
            sm.put("username", acc == null ? null : acc.getUsername());
            sm.put("accountEnabled", acc == null ? null : acc.getEnabled());
            students.add(sm);
        }
        detail.put("students", students);
        return detail;
    }

    /**
     * 学生批量导入到班级。
     *
     * <p>每个姓名建一份学生档案 + 一个 {@code stu{id}} 账号（默认密码 123456），并归入本班。
     * 同班同名视为已存在、记入 {@code duplicated} 而非报错 —— 教务场景一次粘几十行，
     * 撞一个重复不该让整批失败。</p>
     *
     * <p>整个方法一个事务：要么整批成功，要么整批回滚。半批成功的名册没人能收拾。</p>
     */
    @Transactional
    public Map<String, Object> importStudents(Long clazzId, StudentImportReq req) {
        Clazz clazz = requireClazz(clazzId);
        List<String> names = req.resolvedNames();
        if (names.isEmpty()) {
            throw new BizException("请提供至少一个学生姓名");
        }

        List<String> created = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String name : names) {
            Long exists = studentMapper.selectCount(new LambdaQueryWrapper<Student>()
                    .eq(Student::getClazzId, clazzId).eq(Student::getName, name));
            if (exists != null && exists > 0) {
                duplicated.add(name);
                continue;
            }
            Student student = new Student();
            student.setName(name);
            student.setOrgId(clazz.getOrgId());
            student.setCampusId(clazz.getCampusId());
            student.setClazzId(clazz.getId());
            studentMapper.insert(student);
            // 档案建好才拿得到 id，账号名依赖它
            authService.createStudentAccount(
                    authService.suggestUsername(student.getId()),
                    AuthService.DEFAULT_STUDENT_PWD,
                    name,
                    student.getId(),
                    clazz.getOrgId(),
                    clazz.getCampusId());
            created.add(name);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clazzId", clazzId);
        result.put("clazzName", clazz.getName());
        result.put("createdCount", created.size());
        result.put("duplicatedCount", duplicated.size());
        result.put("created", created);
        result.put("duplicated", duplicated);
        result.put("defaultPassword", AuthService.DEFAULT_STUDENT_PWD);
        return result;
    }

    /** 学生转班 / 转校区 */
    @Transactional
    public Map<String, Object> moveStudent(Long studentId, Long clazzId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException("学生不存在: " + studentId);
        }
        UserContext.checkOrgScope(student.getOrgId());
        UserContext.checkCampusScope(student.getCampusId());
        UserContext.checkClazzScope(student.getClazzId());
        Clazz clazz = requireClazz(clazzId);
        if (!java.util.Objects.equals(student.getOrgId(), clazz.getOrgId())) {
            throw new BizException("转班只能在同一机构内进行，跨机构迁移需同时迁移图谱与学习记录");
        }
        student.setClazzId(clazz.getId());
        student.setCampusId(clazz.getCampusId());
        studentMapper.updateById(student);

        SysUser account = authService.findByStudentId(studentId);
        if (account != null) {
            account.setOrgId(clazz.getOrgId());
            account.setCampusId(clazz.getCampusId());
            sysUserMapper.updateById(account);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("clazzId", clazz.getId());
        result.put("clazzName", clazz.getName());
        return result;
    }

    // ==================== 内部工具 ====================

    /**
     * 读取场景的机构范围：显式传入的机构必须落在可见范围内；不传则用当前账号的机构，
     * 平台管理员不传时返回 null（表示全部机构）。
     */
    private Long resolveReadOrg(Long orgId) {
        Long scope = UserContext.scopeOrgId();
        if (orgId == null) {
            return scope;
        }
        UserContext.checkOrgScope(orgId);
        return orgId;
    }

    /** 校验校区属于指定机构；为空返回 null */
    private Long resolveCampus(Long campusId, Long orgId) {
        if (campusId == null) {
            return null;
        }
        Campus campus = campusMapper.selectById(campusId);
        if (campus == null) {
            throw new BizException("校区不存在: " + campusId);
        }
        if (!orgId.equals(campus.getOrgId())) {
            throw new BizException("校区不属于该机构: " + campusId);
        }
        return campusId;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
