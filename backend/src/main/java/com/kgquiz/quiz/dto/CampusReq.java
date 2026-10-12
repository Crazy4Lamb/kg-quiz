package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 校区新增 / 编辑。{@code orgId} 留空表示操作者自己所属机构（平台管理员必须显式指定）。
 */
public record CampusReq(
        Long orgId,
        @NotBlank String name,
        String code,
        String address) {
}
