package com.kgquiz.quiz.auth;

import java.util.List;

/**
 * 当前请求的登录主体。
 *
 * <p>{@code studentId} 仅学生角色有值；{@code orgId} / {@code campusId} 是租户归属，
 * 平台管理员（ADMIN）两者都为 null，表示可跨机构。</p>
 *
 * <p>{@code clazzIds} 仅教师角色会被填充（该教师带的班），其余角色为空列表。</p>
 */
public record CurrentUser(
        Long userId,
        String username,
        UserRole role,
        String displayName,
        Long studentId,
        Long orgId,
        Long campusId,
        List<Long> clazzIds) {

    public CurrentUser {
        clazzIds = clazzIds == null ? List.of() : List.copyOf(clazzIds);
    }

    public boolean isStudent() {
        return role == UserRole.STUDENT;
    }

    /** 平台管理员：不属于任何机构，可跨机构 */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /** 机构管理员：只管本机构 */
    public boolean isOrgAdmin() {
        return role == UserRole.ORG_ADMIN;
    }

    /**
     * 数据可见的机构范围：平台管理员返回 null（表示不做机构过滤），其余角色返回所属机构。
     * 所有按机构过滤的查询都应当使用这个值，不要直接读 {@link #orgId()}。
     */
    public Long scopeOrgId() {
        return isAdmin() ? null : orgId;
    }

    public boolean hasClazz(Long clazzId) {
        return clazzId != null && clazzIds.contains(clazzId);
    }
}
