package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理员手动建账号（平台管理员 / 机构管理员 / 教师，或为学生补账号）。
 *
 * <p>归属：机构管理员、教师必须落在具体机构；学生账号通常由建学生时自动开户。
 * 平台管理员（ADMIN）创建带机构的账号时必须显式指定 {@code orgId}；
 * 其余角色留空即落在操作者自己所属机构。</p>
 */
public record AccountCreateReq(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String role,
        String displayName,
        Long studentId,
        Long orgId,
        Long campusId) {
}
