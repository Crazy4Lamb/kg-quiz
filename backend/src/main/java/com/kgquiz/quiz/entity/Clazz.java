package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 班级 —— 校区下的教学单元。学生按班级归属，教师按班级授课。
 *
 * <p>年级 / 教材 / 学期放在班级上，用于定位该班对应的知识图谱维度。</p>
 */
@Data
@TableName("clazz")
public class Clazz {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构（冗余字段，便于租户过滤时不回表） */
    private Long orgId;

    /** 所属校区 */
    private Long campusId;

    private String name;

    private String grade;

    private String subject;

    private String textbook;

    private String term;

    /** 班主任 sys_user.id */
    private Long headTeacherId;

    /** ACTIVE 在读 / ARCHIVED 归档 */
    private String status;

    private LocalDateTime createdAt;
}
