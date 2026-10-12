package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.service.DemoSeedService;
import com.kgquiz.quiz.service.VectorIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequireRole(UserRole.ADMIN)
@RequiredArgsConstructor
public class AdminController {

    private final VectorIndexService vectorIndexService;
    private final DemoSeedService demoSeedService;

    /** 导入示例数据（仅空库可用）：小学数学五年级上图谱 + 题库 + 学生 */
    @PostMapping("/seed-demo")
    public R<Map<String, Object>> seedDemo() {
        return R.ok(demoSeedService.importDemo());
    }

    /** 全量重建 Qdrant 向量索引 */
    @PostMapping("/vector-index/rebuild")
    public R<Map<String, Object>> rebuild() {
        return R.ok(vectorIndexService.rebuild());
    }

    @PostMapping("/vector-index/status")
    public R<Map<String, Object>> status() {
        return R.ok(Map.of("available", vectorIndexService.available()));
    }
}
