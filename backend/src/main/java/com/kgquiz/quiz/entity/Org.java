package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构 —— 多租户的根。
 *
 * <p>图谱、题库、学生、试卷全部挂在机构下，跨机构数据互不可见。
 * 平台管理员（{@code ADMIN}）不属于任何机构，可跨机构管理。</p>
 */
@Data
@TableName("org")
public class Org {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 机构编码，对外唯一标识 */
    private String code;

    private String contactName;

    private String contactPhone;

    /** ACTIVE 正常 / SUSPENDED 停用 */
    private String status;

    private String remark;

    private LocalDateTime createdAt;
}
