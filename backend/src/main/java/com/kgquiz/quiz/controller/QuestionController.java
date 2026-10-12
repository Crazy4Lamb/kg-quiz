package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.MappingApplyReq;
import com.kgquiz.quiz.dto.MappingAutoReq;
import com.kgquiz.quiz.dto.MappingBatchReq;
import com.kgquiz.quiz.dto.MappingSuggestReq;
import com.kgquiz.quiz.dto.QuestionBatchReq;
import com.kgquiz.quiz.dto.QuestionReq;
import com.kgquiz.quiz.service.QuestionMappingService;
import com.kgquiz.quiz.service.QuestionService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/questions")
@RequireRole({UserRole.TEACHER, UserRole.ADMIN})
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionMappingService questionMappingService;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String subject,
                                        @RequestParam(required = false) String textbook,
                                        @RequestParam(required = false) String questionType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(questionService.list(subject, textbook, questionType, keyword, nodeId, page, size));
    }

    // ==================== 知识点自动识别 ====================

    /** 单题识别：语义召回 + 大模型判定，只给建议不落库 */
    @PostMapping("/mapping/suggest")
    public R<Map<String, Object>> suggest(@Valid @RequestBody MappingSuggestReq req) {
        return R.ok(questionMappingService.suggest(req.questionId(), req.graphId(), req.topK()));
    }

    /** 批量识别（只给建议） */
    @PostMapping("/mapping/batch")
    public R<Map<String, Object>> mappingBatch(@RequestBody MappingBatchReq req) {
        return R.ok(questionMappingService.batchSuggest(req.questionIds(), req.graphId(), req.topK(),
                Boolean.TRUE.equals(req.onlyUnmapped())));
    }

    /** 一键为「还没有映射」的题目识别并写入（可先 dryRun 预演） */
    @PostMapping("/mapping/auto")
    public R<Map<String, Object>> mappingAuto(@Valid @RequestBody MappingAutoReq req) {
        return R.ok(questionMappingService.autoMapUnmapped(req.graphId(), req.minConfidence(),
                req.maxQuestions(), Boolean.TRUE.equals(req.dryRun())));
    }

    /** 确认写入映射 */
    @PostMapping("/mapping/apply")
    public R<Map<String, Object>> mappingApply(@Valid @RequestBody MappingApplyReq req) {
        return R.ok(questionMappingService.apply(req.questionId(), req.nodeIds(),
                Boolean.TRUE.equals(req.replace())));
    }

    /** 解除某个知识点映射 */
    @DeleteMapping("/mapping/{questionId}/{nodeId}")
    public R<Map<String, Object>> mappingRemove(@PathVariable Long questionId, @PathVariable Long nodeId) {
        return R.ok(questionMappingService.unmap(questionId, nodeId));
    }

    /** 批量导入题目：先全量校验，有错整体不落库并逐行返回原因 */
    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@Valid @RequestBody QuestionBatchReq req) {
        return R.ok(questionService.batchCreate(req));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(questionService.detail(id));
    }

    @PostMapping
    public R<Object> create(@Valid @RequestBody QuestionReq req) {
        return R.ok(questionService.create(req));
    }

    @PutMapping("/{id}")
    public R<Object> update(@PathVariable Long id, @Valid @RequestBody QuestionReq req) {
        return R.ok(questionService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return R.ok();
    }
}
