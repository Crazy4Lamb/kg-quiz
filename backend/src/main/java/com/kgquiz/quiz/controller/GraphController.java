package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.GraphCopyReq;
import com.kgquiz.quiz.dto.GraphCreateReq;
import com.kgquiz.quiz.dto.NodeBatchReq;
import com.kgquiz.quiz.dto.NodeCreateReq;
import com.kgquiz.quiz.dto.NodeReq;
import com.kgquiz.quiz.dto.PositionReq;
import com.kgquiz.quiz.dto.RelationReq;
import com.kgquiz.quiz.service.GraphService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graphs")
@RequireRole({UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String subject,
                                             @RequestParam(required = false) String textbook,
                                             @RequestParam(required = false) String grade,
                                             @RequestParam(required = false) String term) {
        return R.ok(graphService.list(subject, textbook, grade, term).stream()
                .map(g -> Map.<String, Object>of(
                        "id", g.getId(),
                        "name", g.getName(),
                        "subject", g.getSubject(),
                        "textbook", g.getTextbook(),
                        "grade", g.getGrade(),
                        "term", g.getTerm(),
                        "description", g.getDescription() == null ? "" : g.getDescription(),
                        "createdAt", g.getCreatedAt() == null ? "" : g.getCreatedAt().toString()))
                .toList());
    }

    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody GraphCreateReq req) {
        var g = graphService.create(req);
        return R.ok(Map.of("id", g.getId(), "name", g.getName()));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(graphService.detail(id));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody GraphCreateReq req) {
        graphService.update(id, req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        graphService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/copy")
    public R<Map<String, Object>> copy(@PathVariable Long id, @Valid @RequestBody GraphCopyReq req) {
        return R.ok(graphService.copy(id, req));
    }

    @PostMapping("/{id}/nodes")
    public R<Object> createNode(@PathVariable Long id, @Valid @RequestBody NodeCreateReq req) {
        return R.ok(graphService.createNode(id, req));
    }

    /** 批量导入知识点（缩进层级 → 自动生成 CONTAINS 树） */
    @PostMapping("/{id}/nodes/batch")
    public R<Map<String, Object>> batchNodes(@PathVariable Long id, @Valid @RequestBody NodeBatchReq req) {
        return R.ok(graphService.batchCreateNodes(id, req));
    }

    @PutMapping("/{id}/nodes/{nodeId}")
    public R<Object> updateNode(@PathVariable Long id, @PathVariable Long nodeId,
                                 @Valid @RequestBody NodeReq req) {
        return R.ok(graphService.updateNode(id, nodeId, req));
    }

    @DeleteMapping("/{id}/nodes/{nodeId}")
    public R<Void> deleteNode(@PathVariable Long id, @PathVariable Long nodeId) {
        graphService.deleteNode(id, nodeId);
        return R.ok();
    }

    @PutMapping("/{id}/nodes/positions")
    public R<Void> savePositions(@PathVariable Long id, @Valid @RequestBody PositionReq req) {
        graphService.savePositions(id, req);
        return R.ok();
    }

    @PostMapping("/{id}/relations")
    public R<Object> createRelation(@PathVariable Long id, @Valid @RequestBody RelationReq req) {
        return R.ok(graphService.createRelation(id, req));
    }

    @PutMapping("/{id}/relations/{relationId}")
    public R<Object> updateRelation(@PathVariable Long id, @PathVariable Long relationId,
                                     @Valid @RequestBody RelationReq req) {
        return R.ok(graphService.updateRelation(id, relationId, req));
    }

    @DeleteMapping("/{id}/relations/{relationId}")
    public R<Void> deleteRelation(@PathVariable Long id, @PathVariable Long relationId) {
        graphService.deleteRelation(id, relationId);
        return R.ok();
    }
}
