package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.ContentBatchReq;
import com.kgquiz.quiz.dto.ContentGenerateReq;
import com.kgquiz.quiz.dto.ContentUpdateReq;
import com.kgquiz.quiz.service.ContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识点教学内容：大模型生成 → 教师编辑 → 发布 → 学生学习。
 *
 * <p>未配置大模型 API Key 时不报错：自动降级为规则模板，响应里的
 * {@code generatedBy} 会如实标注 {@code LLM} / {@code TEMPLATE} / {@code TEACHER}。</p>
 */
@RestController
@RequestMapping("/api/contents")
@RequireRole({UserRole.STUDENT, UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    // ==================== 生成与编辑（教师 / 管理员）====================

    /** 大模型可用状态与可选内容类型 */
    @GetMapping("/status")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> status() {
        return R.ok(contentService.status());
    }

    /** 某知识点的全部内容（教师视角，含草稿） */
    @GetMapping("/node/{nodeId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> byNode(@PathVariable Long nodeId) {
        return R.ok(contentService.listByNode(nodeId));
    }

    /** 图谱级内容一览：哪些知识点已有内容、哪些还是草稿 */
    @GetMapping("/graph/{graphId}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> byGraph(@PathVariable Long graphId) {
        return R.ok(contentService.listByGraph(graphId));
    }

    /** 为知识点生成内容（大模型优先，失败降级模板） */
    @PostMapping("/generate")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> generate(@Valid @RequestBody ContentGenerateReq req) {
        return R.ok(contentService.generate(req.nodeId(), req.type(), req.extraInstruction(),
                Boolean.TRUE.equals(req.replaceExisting())));
    }

    /** 批量为整张图谱生成内容 */
    @PostMapping("/batch")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> batch(@Valid @RequestBody ContentBatchReq req) {
        return R.ok(contentService.batchGenerate(req.graphId(), req.types(),
                Boolean.TRUE.equals(req.onlyMissing()), req.extraInstruction()));
    }

    /** 教师手工编辑内容 */
    @PutMapping("/{id}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody ContentUpdateReq req) {
        return R.ok(contentService.update(id, req.title(), req.summary(), req.body()));
    }

    /** 发布 / 撤回（body: {"status":"PUBLISHED"} 或 {"status":"DRAFT"}） */
    @PutMapping("/{id}/status")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> setStatus(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {
        return R.ok(contentService.setStatus(id, body.get("status")));
    }

    @DeleteMapping("/{id}")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Void> delete(@PathVariable Long id) {
        contentService.delete(id);
        return R.ok();
    }

    // ==================== 学生学习 ====================

    /** 学生视角：只返回已发布内容 */
    @GetMapping("/node/{nodeId}/published")
    public R<List<Map<String, Object>>> published(@PathVariable Long nodeId) {
        return R.ok(contentService.studentContents(nodeId));
    }
}
