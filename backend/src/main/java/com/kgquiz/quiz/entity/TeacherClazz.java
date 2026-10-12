package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师带班（授课）关系 —— 教师与班级的多对多。
 *
 * <p>{@code subject} 为空表示班主任 / 全科；填了科目表示"教这个班的这门课"。
 * 同一教师在同一班级可授多门课，故唯一键是三元组。</p>
 */
@Data
@TableName("teacher_clazz")
public class TeacherClazz {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 教师 sys_user.id */
    private Long teacherId;

    private Long clazzId;

    /** 授课科目，空表示班主任 / 全科 */
    private String subject;

    private LocalDateTime createdAt;
}
