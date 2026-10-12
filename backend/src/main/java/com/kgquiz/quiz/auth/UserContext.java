package com.kgquiz.quiz.auth;

import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.UnauthorizedException;

/**
 * 请求级登录主体容器（ThreadLocal），由 AuthInterceptor 写入与清理。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前登录用户，未登录抛 401 */
    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new UnauthorizedException("未登录或登录已过期，请重新登录");
        }
        return user;
    }

    public static CurrentUser peek() {
        return HOLDER.get();
    }

    /**
     * 学生只能操作自己的数据；教师/管理员不受限。
     * Controller 中凡带 studentId 路径参数或入参的接口都要过这一关。
     */
    public static void checkStudentScope(Long studentId) {
        CurrentUser user = require();
        if (!user.isStudent()) {
            return;
        }
        if (user.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        if (!user.studentId().equals(studentId)) {
            throw new ForbiddenException("只能查看自己的学习数据");
        }
    }

    /**
     * 把入参的 studentId 收敛到学生自己的范围：
     * 学生显式传了别人的 id 直接 403（不静默改写，避免掩盖越权尝试）；不传则默认查自己。
     */
    public static Long scopeStudentId(Long requested) {
        CurrentUser user = require();
        if (!user.isStudent()) {
            return requested;
        }
        if (user.studentId() == null) {
            throw new ForbiddenException("当前学生账号未绑定学生档案，请联系老师处理");
        }
        if (requested != null && !requested.equals(user.studentId())) {
            throw new ForbiddenException("只能查看自己的学习数据");
        }
        return user.studentId();
    }

    // ==================== 租户范围 ====================

    /**
     * 当前账号可见的机构范围：平台管理员返回 null（跨机构），其余返回所属机构。
     * 业务查询一律用它做机构过滤。
     *
     * <p>非管理员却没有机构归属时直接 403 —— 否则会退化成「不过滤」，
     * 把全平台数据都暴露出去。</p>
     */
    public static Long scopeOrgId() {
        CurrentUser user = require();
        if (user.isAdmin()) {
            return null;
        }
        if (user.orgId() == null) {
            throw new ForbiddenException("当前账号未归属任何机构，请联系平台管理员");
        }
        return user.orgId();
    }

    /** 目标数据是否落在当前账号的机构范围内；越界一律 403 */
    public static void checkOrgScope(Long targetOrgId) {
        CurrentUser user = require();
        if (user.isAdmin()) {
            return;
        }
        if (user.orgId() == null) {
            throw new ForbiddenException("当前账号未归属任何机构，请联系平台管理员");
        }
        if (targetOrgId == null || !user.orgId().equals(targetOrgId)) {
            throw new ForbiddenException("只能访问本机构的数据");
        }
    }

    /**
     * 班级范围校验：教师只能操作自己带的班；机构管理员与平台管理员不受此限
     * （机构边界由 {@link #checkOrgScope} 兜底）。
     */
    public static void checkClazzScope(Long clazzId) {
        CurrentUser user = require();
        if (user.isStudent()) {
            throw new ForbiddenException("学生账号无权执行该操作");
        }
        if (user.role() == UserRole.TEACHER && !user.hasClazz(clazzId)) {
            throw new ForbiddenException("只能操作自己带的班级");
        }
    }

    /**
     * 新建数据时的归属机构。
     *
     * <p>平台管理员必须显式指定机构（它可以跨机构建数据）；其余角色一律落在自己所属机构，
     * 传了别人的机构直接 403（不静默改写）。</p>
     */
    public static Long resolveWriteOrgId(Long requested) {
        CurrentUser user = require();
        if (user.isAdmin()) {
            if (requested == null) {
                throw new BizException("请指定数据归属的机构");
            }
            return requested;
        }
        if (user.orgId() == null) {
            throw new ForbiddenException("当前账号未归属任何机构，请联系平台管理员");
        }
        if (requested != null && !user.orgId().equals(requested)) {
            throw new ForbiddenException("只能管理本机构的数据");
        }
        return user.orgId();
    }

    /** 目标数据是否落在当前账号的机构范围内（不做 403，返回布尔） */
    public static boolean inScope(Long targetOrgId) {
        Long scope = scopeOrgId();
        return scope == null || scope.equals(targetOrgId);
    }
}
