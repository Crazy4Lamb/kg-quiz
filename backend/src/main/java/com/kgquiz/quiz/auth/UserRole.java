package com.kgquiz.quiz.auth;

import com.kgquiz.quiz.common.BizException;

/**
 * 系统角色。平台管理员跨机构，机构管理员管本机构，教师管教学资源，学生只看自己的数据。
 */
public enum UserRole {

    /** 平台管理员：全部权限，可跨机构，含机构管理、账号管理与系统运维 */
    ADMIN("平台管理员"),

    /** 机构管理员：本机构的校区 / 班级 / 教师 / 学生 + 全部教学资源 */
    ORG_ADMIN("机构管理员"),

    /** 教师：本机构内的图谱、题库、学生、出题 */
    TEACHER("教师"),

    /** 学生：自己的图谱/报告/错题本/练习 */
    STUDENT("学生");

    private final String label;

    UserRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 角色是否满足 {@code required} 要求（含层级包含）。
     *
     * <p>规则刻意保守，只做「向下包含」：</p>
     * <ul>
     *   <li>ADMIN → 满足 ORG_ADMIN / TEACHER（平台管理员可做机构与教学管理），
     *       <b>不</b>满足 STUDENT（学生接口是「以学生身份作答」，管理员不应代答）</li>
     *   <li>ORG_ADMIN → 满足 TEACHER（机构管理员可行使本机构教学管理），
     *       <b>不</b>满足 ADMIN（跨机构、账号与运维仅平台管理员）</li>
     *   <li>TEACHER / STUDENT → 只满足自身</li>
     * </ul>
     */
    public boolean implies(UserRole required) {
        if (this == required) {
            return true;
        }
        return switch (this) {
            case ADMIN -> required == ORG_ADMIN || required == TEACHER;
            case ORG_ADMIN -> required == TEACHER;
            default -> false;
        };
    }

    public static UserRole of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("角色不能为空");
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("不支持的角色: " + value + "（可选 ADMIN / ORG_ADMIN / TEACHER / STUDENT）");
        }
    }
}
