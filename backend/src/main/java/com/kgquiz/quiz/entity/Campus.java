package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校区 —— 机构下的办学点。班级、教师归属在校区下。
 */
@Data
@TableName("campus")
public class Campus {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构 */
    private Long orgId;

    private String name;

    /** 校区编码 */
    private String code;

    private String address;

    /** ACTIVE 正常 / SUSPENDED 停用 */
    private String status;

    private LocalDateTime createdAt;
}
