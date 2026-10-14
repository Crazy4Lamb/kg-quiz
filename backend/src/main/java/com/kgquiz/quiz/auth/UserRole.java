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

    /**
     * 校区管理员：一个校区内的负责人。
     *
     * <p>与 {@link #ORG_ADMIN} 的差别只在<b>可见范围</b>，不在能力：能管的事一样多
     * （班级、教师、学生、教学资源），但被 {@code campusId} 限死在绑定的那个校区里，
     * 看不到也改不了本机构其他校区。</p>
     *
     * <p>关键约束：<b>必须绑定 campusId</b>。没绑定的校区管理员在任何校区级判定上都会
     * 被 {@code UserContext.checkCampusScope} 拦下 —— 这是刻意的，宁可不可用也不给全机构默认权限。
     */
    CAMPUS_ADMIN("校区管理员"),

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
     *   <li>ADMIN → 满足 ORG_ADMIN / CAMPUS_ADMIN / TEACHER（平台管理员可做机构与教学管理），
     *       <b>不</b>满足 STUDENT（学生接口是「以学生身份作答」，管理员不应代答）</li>
     *   <li>ORG_ADMIN → 满足 CAMPUS_ADMIN / TEACHER（机构管理员可行使本机构教学管理），
     *       <b>不</b>满足 ADMIN（跨机构、账号与运维仅平台管理员）</li>
     *   <li>CAMPUS_ADMIN → 满足 TEACHER（能力等同教师，范围窄化到本校区）</li>
     *   <li>TEACHER / STUDENT → 只满足自身</li>
     * </ul>
     *
     * <p>注意：包含关系只管「能不能进这个接口」，<b>不管能看哪些数据</b>。
     * 校区管理员满足 TEACHER 的所有接口，但每个接口里看到的数据仍被
     * {@code UserContext} 按 campusId 过滤 —— 两者是正交的两件事。</p>
     */
    public boolean implies(UserRole required) {
        if (this == required) {
            return true;
        }
        return switch (this) {
            case ADMIN -> required == ORG_ADMIN || required == CAMPUS_ADMIN || required == TEACHER;
            case ORG_ADMIN -> required == CAMPUS_ADMIN || required == TEACHER;
            case CAMPUS_ADMIN -> required == TEACHER;
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
            throw new BizException("不支持的角色: " + value
                    + "（可选 ADMIN / ORG_ADMIN / CAMPUS_ADMIN / TEACHER / STUDENT）");
        }
    }
}
