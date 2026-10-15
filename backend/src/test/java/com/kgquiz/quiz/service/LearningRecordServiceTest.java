package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kgquiz.quiz.entity.LearningRecord;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.mapper.LearningRecordMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LearningRecordServiceTest {
    private final LearningRecordMapper records = mock(LearningRecordMapper.class);
    private final StudentAccessService access = mock(StudentAccessService.class);
    private final LearningRecordService service = new LearningRecordService(records, mock(GraphStore.class), access);
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), LearningRecord.class);
    }
    @Test void recentActivityQueriesAllKnowledgePoints() {
        var r = record(9L, "PRACTICE", 1.0, 1);
        when(records.selectList(any())).thenReturn(List.of(r));
        assertTrue(service.recentNodeActivity(1L, 2).containsKey(9L));
        ArgumentCaptor<LambdaQueryWrapper<LearningRecord>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(records).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("node_id IS NOT NULL")); assertTrue(sql.contains("action <>"));
        assertFalse(query.getValue().getParamNameValuePairs().containsValue(1));
    }
    @Test void disabledWindowDoesNotQueryOrPenalizeToday() {
        assertTrue(service.recentNodeActivity(1L, 0).isEmpty()); verifyNoInteractions(records);
    }
    @Test void scoreRateIsWeightedByQuestionCountAndExcludesCorrectionEvents() {
        when(records.selectList(any())).thenReturn(List.of(
                record(null, "PRACTICE", 1.0, 1), record(null, "PRACTICE", 0.0, 9), record(null, "ADJUST", 1.0, 0)));
        var stats = service.stats(1L, 7);
        assertEquals(0.1, stats.get("avgScoreRate")); assertEquals(10, stats.get("questionCount"));
        assertEquals(7, ((List<?>) stats.get("dailyTrend")).size());
    }
    @Test void importedStudentCanRecordWithoutGraphCopy() {
        Student student = new Student(); student.setId(1L); student.setOrgId(7L);
        when(access.requireStudent(1L)).thenReturn(student);
        service.recordView(1L, 9L, null, null, null);
        ArgumentCaptor<LearningRecord> captured = ArgumentCaptor.forClass(LearningRecord.class);
        verify(records).insert(captured.capture()); assertEquals(7L, captured.getValue().getOrgId());
    }
    private LearningRecord record(Long node, String action, double score, int questions) {
        var r = new LearningRecord(); r.setNodeId(node); r.setAction(action);
        r.setScoreRate(BigDecimal.valueOf(score)); r.setQuestionCount(questions); r.setCreatedAt(LocalDateTime.now()); return r;
    }
}
