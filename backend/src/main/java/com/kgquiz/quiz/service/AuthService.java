package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.PasswordEncoder;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.UnauthorizedException;
import com.kgquiz.quiz.dto.AccountCreateReq;
import com.kgquiz.quiz.entity.Campus;
import com.kgquiz.quiz.entity.Clazz;
import com.kgquiz.quiz.entity.Org;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.entity.SysToken;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 账号与登录会话。轻量自研方案：UUID 令牌落库 + 过期时间，不引 Spring Security。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 令牌有效期 */
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    public static final String DEFAULT_ADMIN_USER = "admin";
    public static final String DEFAULT_ADMIN_PWD = "admin123";
    public static final String DEFAULT_TEACHER_USER = "teacher";
    public static final String DEFAULT_TEACHER_PWD = "teacher123";
    public static final String DEFAULT_STUDENT_PWD = "123456";

    /** 默认机构树的名称常量，供 bootstrap 与种子数据共用 */
    public static final String DEFAULT_ORG_NAME = "示例教育集团";
    public static final String DEFAULT_ORG_CODE = "DEMO";
    public static final String DEFAULT_CAMPUS_NAME = "示例校区";
    public static final String DEFAULT_CLAZZ_NAME = "五年级1班";

    private final SysUserMapper sysUserMapper;
    private final SysTokenMapper sysTokenMapper;
    private final StudentMapper studentMapper;
    private final TeacherClazzMapper teacherClazzMapper;
    private final OrgMapper orgMapper;
    private final CampusMapper campusMapper;
    private final ClazzMapper clazzMapper;

    /** 默认机构树的三级 id（org → campus → clazz） */
    public record OrgScope(Long orgId, Long campusId, Long clazzId) {
    }

    // ==================== 登录 / 登出 ====================

    @Transactional
    public Map<String, Object> login(String username, String rawPassword) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim()));
        // 账号不存在与密码错误返回同一提示，避免暴露账号是否注册
        if (user == null || !PasswordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw new BizException("账号已停用，请联系管理员");
        }

        // 顺手清掉该用户已过期的令牌
        sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>()
                .eq(SysToken::getUserId, user.getId())
                .lt(SysToken::getExpireAt, LocalDateTime.now()));

        LocalDateTime expireAt = LocalDateTime.now().plus(TOKEN_TTL);
        SysToken token = new SysToken();
        token.setToken(UUID.randomUUID().toString().replace("-", ""));
        token.setUserId(user.getId());
        token.setExpireAt(expireAt);
        sysTokenMapper.insert(token);

        user.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("登录成功: {} ({})", user.getUsername(), user.getRole());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token.getToken());
        result.put("expiresAt", expireAt);
        result.put("user", toView(user));
        return result;
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getToken, token));
        }
    }

    /** 校验令牌并还原登录主体；失效抛 401 */
    public CurrentUser resolve(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("未登录，请先登录");
        }
        SysToken session = sysTokenMapper.selectOne(
                new LambdaQueryWrapper<SysToken>().eq(SysToken::getToken, token));
        if (session == null) {
            throw new UnauthorizedException("登录已失效，请重新登录");
        }
        if (session.getExpireAt() != null && session.getExpireAt().isBefore(LocalDateTime.now())) {
            sysTokenMapper.deleteById(session.getId());
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        SysUser user = sysUserMapper.selectById(session.getUserId());
        if (user == null) {
            throw new UnauthorizedException("账号不存在，请重新登录");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw new UnauthorizedException("账号已停用，请联系管理员");
        }
        UserRole role = UserRole.of(user.getRole());
        return new CurrentUser(user.getId(), user.getUsername(), role, user.getDisplayName(),
                user.getStudentId(), user.getOrgId(), user.getCampusId(), loadClazzIds(user.getId(), role));
    }

    /** 教师带班的班级 id 列表；其余角色为空 */
    private List<Long> loadClazzIds(Long userId, UserRole role) {
        if (role != UserRole.TEACHER) {
            return List.of();
        }
        return teacherClazzMapper.selectList(new LambdaQueryWrapper<TeacherClazz>()
                        .eq(TeacherClazz::getTeacherId, userId))
                .stream().map(TeacherClazz::getClazzId).filter(Objects::nonNull).distinct().toList();
    }

    /** 当前登录者信息（学生额外带学生姓名，前端据此展示"我的学习"） */
    public Map<String, Object> profile(CurrentUser current) {
        SysUser user = requireUser(current.userId());
        Map<String, Object> view = toView(user);
        view.put("lastLoginAt", user.getLastLoginAt());
        if (user.getStudentId() != null) {
            Student student = studentMapper.selectById(user.getStudentId());
            if (student != null) {
                view.put("studentName", student.getName());
            }
        }
        return view;
    }

    // ==================== 账号管理 ====================

    /** 账号列表：按当前账号的机构范围过滤（平台管理员看全部） */
    public List<Map<String, Object>> listAccounts() {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getRole).orderByAsc(SysUser::getId);
        CurrentUser current = UserContext.peek();
        Long scope = current == null ? null : current.scopeOrgId();
        if (scope != null) {
            wrapper.eq(SysUser::getOrgId, scope);
        }
        List<SysUser> users = sysUserMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser user : users) {
            Map<String, Object> item = toView(user);
            item.put("lastLoginAt", user.getLastLoginAt());
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createAccount(AccountCreateReq req) {
        UserRole role = UserRole.of(req.role());
        CurrentUser operator = UserContext.require();
        // 机构管理员只能建本机构内的角色，不能造平台管理员
        if (!operator.isAdmin() && (role == UserRole.ADMIN || role == UserRole.ORG_ADMIN)) {
            throw new com.kgquiz.quiz.common.ForbiddenException("机构管理员不能创建管理员账号");
        }
        String username = req.username().trim();
        if (existsUsername(username, null)) {
            throw new BizException("登录名已存在: " + username);
        }
        PasswordEncoder.validateStrength(req.password());
        Long studentId = req.studentId();
        if (role == UserRole.STUDENT) {
            if (studentId == null) {
                throw new BizException("学生账号必须绑定学生档案");
            }
            if (studentMapper.selectById(studentId) == null) {
                throw new BizException("学生不存在: " + studentId);
            }
            if (findByStudentId(studentId) != null) {
                throw new BizException("该学生已有登录账号");
            }
        } else {
            studentId = null;
        }
        // 归属：平台管理员是唯一允许「无机构」的角色
        Long orgId = null;
        Long campusId = req.campusId();
        if (role != UserRole.ADMIN) {
            orgId = UserContext.resolveWriteOrgId(req.orgId());
            // 校区必须属于该机构，否则会串租户
            if (campusId != null) {
                Campus campus = campusMapper.selectById(campusId);
                if (campus == null || !orgId.equals(campus.getOrgId())) {
                    throw new BizException("校区不存在或不属于该机构: " + campusId);
                }
            }
        } else {
            orgId = req.orgId();
            campusId = null;
        }
        if (role == UserRole.ORG_ADMIN && orgId == null) {
            throw new BizException("机构管理员必须归属一个机构");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordEncoder.encode(req.password()));
        user.setRole(role.name());
        user.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim());
        user.setStudentId(studentId);
        user.setOrgId(orgId);
        user.setCampusId(campusId);
        user.setEnabled(1);
        sysUserMapper.insert(user);
        return toView(user);
    }

    /** 供 StudentService 在同一个事务里开户（学生账号随学生一起落机构/校区/班级） */
    @Transactional
    public SysUser createStudentAccount(String username, String rawPassword, String displayName, Long studentId,
                                        Long orgId, Long campusId) {
        String uname = username.trim();
        if (existsUsername(uname, null)) {
            throw new BizException("登录名已存在: " + uname);
        }
        PasswordEncoder.validateStrength(rawPassword);
        SysUser user = new SysUser();
        user.setUsername(uname);
        user.setPassword(PasswordEncoder.encode(rawPassword));
        user.setRole(UserRole.STUDENT.name());
        user.setDisplayName(displayName);
        user.setStudentId(studentId);
        user.setOrgId(orgId);
        user.setCampusId(campusId);
        user.setEnabled(1);
        sysUserMapper.insert(user);
        return user;
    }

    /** 未指定登录名时给学生生成默认账号 stu{studentId} */
    public String suggestUsername(Long studentId) {
        String base = "stu" + studentId;
        String candidate = base;
        int i = 1;
        while (existsUsername(candidate, null)) {
            candidate = base + "_" + (i++);
        }
        return candidate;
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = requireUser(userId);
        UserContext.checkOrgScope(user.getOrgId());
        PasswordEncoder.validateStrength(newPassword);
        user.setPassword(PasswordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
        // 改密后强制下线
        sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getUserId, userId));
    }

    @Transactional
    public void changeOwnPassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = requireUser(userId);
        if (!PasswordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException("原密码不正确");
        }
        PasswordEncoder.validateStrength(newPassword);
        if (PasswordEncoder.matches(newPassword, user.getPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }
        user.setPassword(PasswordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
        sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getUserId, userId));
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        SysUser user = requireUser(userId);
        UserContext.checkOrgScope(user.getOrgId());
        if (!enabled && isLastEnabledAdmin(user)) {
            throw new BizException("至少保留一个启用状态的管理员账号");
        }
        user.setEnabled(enabled ? 1 : 0);
        sysUserMapper.updateById(user);
        if (!enabled) {
            sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getUserId, userId));
        }
    }

    @Transactional
    public void deleteAccount(Long userId, Long operatorId) {
        SysUser user = requireUser(userId);
        UserContext.checkOrgScope(user.getOrgId());
        if (userId.equals(operatorId)) {
            throw new BizException("不能删除当前正在使用的账号");
        }
        if (isLastEnabledAdmin(user)) {
            throw new BizException("至少保留一个启用状态的管理员账号");
        }
        sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getUserId, userId));
        sysUserMapper.deleteById(userId);
    }

    /** 是否为最后一个启用中的管理员（禁止把系统锁死） */
    private boolean isLastEnabledAdmin(SysUser user) {
        if (!UserRole.ADMIN.name().equals(user.getRole())) {
            return false;
        }
        return sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, UserRole.ADMIN.name())
                .eq(SysUser::getEnabled, 1)) <= 1;
    }

    /** 删除学生时级联清账号与会话 */
    @Transactional
    public void deleteByStudentId(Long studentId) {
        List<SysUser> users = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getStudentId, studentId));
        for (SysUser user : users) {
            sysTokenMapper.delete(new LambdaQueryWrapper<SysToken>().eq(SysToken::getUserId, user.getId()));
            sysUserMapper.deleteById(user.getId());
        }
    }

    public SysUser findByStudentId(Long studentId) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentId, studentId).last("limit 1"));
    }

    // ==================== 启动初始化 ====================

    /**
     * 首次启动补齐默认机构树、四类默认账号，并把历史数据回填到默认机构，
     * 保证 demo 开箱即可登录、且不会因为「无机构归属」被租户过滤挡住。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapAccounts() {
        try {
            OrgScope scope = ensureDefaultScope();

            if (countByRole(UserRole.ADMIN) == 0) {
                createSeedAccount(DEFAULT_ADMIN_USER, DEFAULT_ADMIN_PWD, UserRole.ADMIN, "系统管理员", null, null, null);
                log.warn("已创建默认平台管理员账号: {} / {}", DEFAULT_ADMIN_USER, DEFAULT_ADMIN_PWD);
            }
            if (countByRole(UserRole.TEACHER) == 0) {
                SysUser teacher = createSeedAccount(DEFAULT_TEACHER_USER, DEFAULT_TEACHER_PWD, UserRole.TEACHER,
                        "示例教师", null, scope.orgId(), scope.campusId());
                bindTeacherClazz(teacher.getId(), scope.clazzId());
                log.warn("已创建默认教师账号: {} / {}", DEFAULT_TEACHER_USER, DEFAULT_TEACHER_PWD);
            }

            // 回填：历史学生与教师账号若还没有机构归属，挂到默认机构树上
            backfillOrgBindings(scope);

            // 组织层首次建立（全库还没有任何带班关系）时，把已有教师挂到默认班级 ——
            // 否则教师登录后带不了任何班，看不到学生。之后不再自动补，尊重人工调整。
            long bindings = teacherClazzMapper.selectCount(new LambdaQueryWrapper<TeacherClazz>());
            if (bindings == 0) {
                List<SysUser> teachers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, UserRole.TEACHER.name())
                        .eq(SysUser::getOrgId, scope.orgId()));
                for (SysUser teacher : teachers) {
                    bindTeacherClazz(teacher.getId(), scope.clazzId());
                    log.warn("已把教师「{}」挂到默认班级「{}」", teacher.getDisplayName(), DEFAULT_CLAZZ_NAME);
                }
            }

            List<Student> students = studentMapper.selectList(null);
            for (Student student : students) {
                if (findByStudentId(student.getId()) != null) {
                    continue;
                }
                String username = suggestUsername(student.getId());
                createSeedAccount(username, DEFAULT_STUDENT_PWD, UserRole.STUDENT, student.getName(),
                        student.getId(), scope.orgId(), student.getCampusId());
                log.warn("已为学生「{}」补建账号: {} / {}", student.getName(), username, DEFAULT_STUDENT_PWD);
            }
        } catch (Exception e) {
            log.error("初始化默认账号失败", e);
        }
    }

    /**
     * 保证默认机构树存在（机构 → 校区 → 班级），返回三级 id。
     * 幂等：已有则直接复用，不会重复建。
     */
    @Transactional
    public OrgScope ensureDefaultScope() {
        Org org = orgMapper.selectOne(new LambdaQueryWrapper<Org>().orderByAsc(Org::getId).last("limit 1"));
        if (org == null) {
            org = new Org();
            org.setName(DEFAULT_ORG_NAME);
            org.setCode(DEFAULT_ORG_CODE);
            org.setStatus("ACTIVE");
            org.setRemark("系统初始化自动创建");
            orgMapper.insert(org);
            log.warn("已创建默认机构: {} (id={})", org.getName(), org.getId());
        }
        Campus campus = campusMapper.selectOne(new LambdaQueryWrapper<Campus>()
                .eq(Campus::getOrgId, org.getId()).orderByAsc(Campus::getId).last("limit 1"));
        if (campus == null) {
            campus = new Campus();
            campus.setOrgId(org.getId());
            campus.setName(DEFAULT_CAMPUS_NAME);
            campus.setStatus("ACTIVE");
            campusMapper.insert(campus);
            log.warn("已创建默认校区: {} (id={})", campus.getName(), campus.getId());
        }
        Clazz clazz = clazzMapper.selectOne(new LambdaQueryWrapper<Clazz>()
                .eq(Clazz::getCampusId, campus.getId()).orderByAsc(Clazz::getId).last("limit 1"));
        if (clazz == null) {
            clazz = new Clazz();
            clazz.setOrgId(org.getId());
            clazz.setCampusId(campus.getId());
            clazz.setName(DEFAULT_CLAZZ_NAME);
            clazz.setGrade("五年级");
            clazz.setSubject("数学");
            clazz.setStatus("ACTIVE");
            clazzMapper.insert(clazz);
            log.warn("已创建默认班级: {} (id={})", clazz.getName(), clazz.getId());
        }
        return new OrgScope(org.getId(), campus.getId(), clazz.getId());
    }

    /** 历史学生 / 教师账号回填机构归属（只在字段为空时补，不覆盖已有归属） */
    private void backfillOrgBindings(OrgScope scope) {
        for (Student student : studentMapper.selectList(null)) {
            if (student.getOrgId() == null) {
                student.setOrgId(scope.orgId());
                student.setCampusId(scope.campusId());
                student.setClazzId(scope.clazzId());
                studentMapper.updateById(student);
            }
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getRole, UserRole.TEACHER.name(), UserRole.STUDENT.name(), UserRole.ORG_ADMIN.name())
                .isNull(SysUser::getOrgId));
        for (SysUser user : users) {
            user.setOrgId(scope.orgId());
            if (UserRole.TEACHER.name().equals(user.getRole())) {
                user.setCampusId(scope.campusId());
                sysUserMapper.updateById(user);
                bindTeacherClazz(user.getId(), scope.clazzId());
                continue;
            }
            // 学生账号的校区跟随学生档案，避免账号与学生记录不一致
            if (UserRole.STUDENT.name().equals(user.getRole()) && user.getStudentId() != null) {
                Student student = studentMapper.selectById(user.getStudentId());
                if (student != null && student.getCampusId() != null) {
                    user.setCampusId(student.getCampusId());
                }
            }
            sysUserMapper.updateById(user);
        }
    }

    /** 教师带班绑定（幂等） */
    @Transactional
    public void bindTeacherClazz(Long teacherId, Long clazzId) {
        if (teacherId == null || clazzId == null) {
            return;
        }
        Long exists = teacherClazzMapper.selectCount(new LambdaQueryWrapper<TeacherClazz>()
                .eq(TeacherClazz::getTeacherId, teacherId).eq(TeacherClazz::getClazzId, clazzId));
        if (exists != null && exists > 0) {
            return;
        }
        TeacherClazz tc = new TeacherClazz();
        tc.setTeacherId(teacherId);
        tc.setClazzId(clazzId);
        tc.setSubject("");
        teacherClazzMapper.insert(tc);
    }

    private long countByRole(UserRole role) {
        return sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, role.name()));
    }

    private SysUser createSeedAccount(String username, String password, UserRole role, String displayName,
                                      Long studentId, Long orgId, Long campusId) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(PasswordEncoder.encode(password));
        user.setRole(role.name());
        user.setDisplayName(displayName);
        user.setStudentId(studentId);
        user.setOrgId(orgId);
        user.setCampusId(campusId);
        user.setEnabled(1);
        sysUserMapper.insert(user);
        return user;
    }

    // ==================== 内部工具 ====================

    private SysUser requireUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException("账号不存在: " + userId);
        }
        return user;
    }

    private boolean existsUsername(String username, Long excludeId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username);
        if (excludeId != null) {
            wrapper.ne(SysUser::getId, excludeId);
        }
        return sysUserMapper.selectCount(wrapper) > 0;
    }

    /** 对外视图：绝不返回密码字段 */
    private Map<String, Object> toView(SysUser user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("role", user.getRole());
        view.put("roleLabel", UserRole.of(user.getRole()).label());
        view.put("displayName", user.getDisplayName());
        view.put("studentId", user.getStudentId());
        view.put("orgId", user.getOrgId());
        view.put("campusId", user.getCampusId());
        view.put("enabled", user.getEnabled());
        return view;
    }
}
