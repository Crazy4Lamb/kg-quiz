package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    /** ADMIN 平台管理员 / ORG_ADMIN 机构管理员 / TEACHER 教师 / STUDENT 学生 */
    private String role;

    /** 所属机构；平台管理员为 null，可跨机构 */
    private Long orgId;

    /** 所属校区（教师归属校区） */
    private Long campusId;

    private String displayName;

    /** 学生角色绑定的 student.id */
    private Long studentId;

    private Integer enabled;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
}
