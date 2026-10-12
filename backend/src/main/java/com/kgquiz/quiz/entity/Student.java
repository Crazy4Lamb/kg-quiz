package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("student")
public class Student {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构（租户） */
    private Long orgId;

    /** 所属校区 */
    private Long campusId;

    /** 所属班级 */
    private Long clazzId;

    private String name;

    private LocalDateTime createdAt;
}
