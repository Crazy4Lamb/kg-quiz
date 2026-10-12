package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 知识点检索：
     * - nodeId 精确查询
     * - keyword 模糊搜索（LIKE + Qdrant 语义合并）
     * - mode: all / like / semantic
     */
    @GetMapping("/nodes")
    public R<Map<String, Object>> search(@RequestParam(required = false) Long nodeId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Long graphId,
                                         @RequestParam(required = false, defaultValue = "all") String mode) {
        return R.ok(searchService.search(nodeId, keyword, graphId, mode));
    }
}
