package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.service.GraphAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 图分析：学习路径推荐 / 前置缺口分析 / 核心知识点排序
 */
@RestController
@RequestMapping("/api/analysis")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class AnalysisController {

    private final GraphAnalysisService analysisService;

    /** 核心知识点排序（PageRank） */
    @GetMapping("/graphs/{graphId}/pagerank")
    public R<Map<String, Object>> pageRank(@PathVariable Long graphId,
                                            @RequestParam(defaultValue = "false") boolean weightBias,
                                            @RequestParam(defaultValue = "20") int topN) {
        return R.ok(analysisService.pageRank(graphId, weightBias, topN));
    }

    /** 学习路径推荐：到目标知识点的最优学习顺序 */
    @GetMapping("/students/{studentId}/path")
    public R<Map<String, Object>> path(@PathVariable Long studentId,
                                       @RequestParam Long targetNodeId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(analysisService.recommendPath(studentId, targetNodeId));
    }

    /** 前置缺口分析：递归找出未掌握的前置知识点 */
    @GetMapping("/students/{studentId}/gap")
    public R<Map<String, Object>> gap(@PathVariable Long studentId,
                                      @RequestParam Long nodeId) {
        UserContext.checkStudentScope(studentId);
        return R.ok(analysisService.gapAnalysis(studentId, nodeId));
    }
}
