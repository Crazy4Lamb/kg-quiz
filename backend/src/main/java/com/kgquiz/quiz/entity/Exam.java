package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam")
public class Exam {

    /** 自适应引擎个性化出题 */
    public static final String SRC_ADAPTIVE = "ADAPTIVE";
    /** 教师从题库手动组卷 */
    public static final String SRC_TEACHER = "TEACHER";
    /** 入学 / 章节诊断卷：交卷后覆盖式初始化掌握度，作为学习起点 */
    public static final String SRC_DIAGNOSIS = "DIAGNOSIS";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构 */
    private Long orgId;

    /** 自适应练习的归属学生；教师组卷为 null */
    private Long studentId;

    private String name;

    private String source;

    private String subject;

    private String textbook;

    private String description;

    /** 出卷人 sys_user.id */
    private Long createdBy;

    /**
     * 出题方案 JSON
     */
    private String plan;

    private String status;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;
}
