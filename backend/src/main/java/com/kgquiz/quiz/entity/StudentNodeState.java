package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生在某知识点上的掌握状态。
 *
 * <p>{@link #mastery} 是「最近一次测评反映的水平」，{@link #practiceCount} 决定这个数字
 * 有多可信 —— 只练过 1 次得 0.9 分，和练过 12 次均分 0.9，是完全不同的两件事。
 * 推荐引擎不能把两者同等对待，所以置信度是独立的一维。</p>
 *
 * <p>{@link #masteryPeak} 是遗忘衰减的下界锚点：衰减把当前掌握度往下拉时，
 * 不应该一路压到 0 让这个知识点永久被推荐为「薄弱」。</p>
 */
@Data
@TableName("student_node_state")
public class StudentNodeState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentGraphId;

    private Long sourceNodeId;

    private BigDecimal mastery;

    /** 累计有效练习次数（置信度分母） */
    private Integer practiceCount;

    /** 最近一次得分率 */
    private BigDecimal lastScoreRate;

    /** 历史最高掌握度 */
    private BigDecimal masteryPeak;

    private LocalDateTime lastPracticedAt;
}
