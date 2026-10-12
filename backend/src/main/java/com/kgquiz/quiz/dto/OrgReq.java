package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 机构新增 / 编辑。
 */
public record OrgReq(
        @NotBlank String name,
        String code,
        String contactName,
        String contactPhone,
        String remark) {
}
