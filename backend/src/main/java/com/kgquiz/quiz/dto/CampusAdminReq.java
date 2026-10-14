package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 校区管理员新增 / 编辑。
 *
 * <p>{@code campusId} 就是这个管理员的<b>权限边界</b>：分配到哪个校区就只能管哪个校区，
 * 不能留空。留空意味着「没绑校区的校区管理员」，而它在所有校区级判定上都会被 403 ——
 * 这是刻意的，宁可不可用也不给全机构默认权限。</p>
 *
 * <p>新建时 {@code username} / {@code password} 必填；编辑时 {@code username} 只读、{@code password} 忽略。</p>
 */
public record CampusAdminReq(
        String username,
        String password,
        @NotBlank String displayName,
        Long campusId) {
}