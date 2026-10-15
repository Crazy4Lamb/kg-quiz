package com.kgquiz.quiz.service;

import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.LearningRecord;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.LearningRecordMapper;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MasteryServiceTest {
    private StudentNodeState state;
    private MasteryService service;
    private LearningRecordMapper records;
    private double alpha;

    @BeforeEach
    void setup() {
        var graphs = mock(StudentGraphMapper.class);
        var states = mock(StudentNodeStateMapper.class);
        records = mock(LearningRecordMapper.class);
        KgProperties properties = new KgProperties();
        alpha = properties.getExam().getLearningRate();
        service = new MasteryService(graphs, states, properties, records);
        StudentGraph graph = new StudentGraph(); graph.setId(2L); graph.setStudentId(1L);
        when(graphs.selectOne(any())).thenReturn(graph);
        state = new StudentNodeState(); state.setId(3L); state.setMastery(new BigDecimal("0.6"));
        state.setPracticeCount(4); state.setMasteryPeak(new BigDecimal("0.8"));
        state.setLastPracticedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        state.setLastScoreRate(new BigDecimal("0.7"));
        when(states.selectOne(any())).thenReturn(state);
    }

    @Test void downwardCorrectionRetainsLearningEvidence() {
        var before = state.getLastPracticedAt();
        var change = service.adjustByRateDelta(1L, Map.of(9L, -0.5)).get(9L);
        assertEquals(0.6 - alpha * 0.5, change.after(), 1e-9);
        assertEquals(4, state.getPracticeCount());
        assertEquals(before, state.getLastPracticedAt());
        assertEquals(new BigDecimal("0.7"), state.getLastScoreRate());
    }

    @Test void diagnosticCorrectionUsesFullObservationWeight() {
        when(records.selectList(any())).thenReturn(List.of(event(1, 10, LearningRecord.A_DIAGNOSIS)));
        service.correctExam(1L, 10L, true, Map.of(9L, -0.4), Map.of(9L, 0.2));
        assertEquals(0.2, state.getMastery().doubleValue(), 1e-9);
        assertEquals(0.2, state.getLastScoreRate().doubleValue(), 1e-9);
        assertEquals(4, state.getPracticeCount());
    }

    @Test void historicCorrectionIsAttenuatedByLaterPractice() {
        when(records.selectList(any())).thenReturn(List.of(
                event(1, 10, LearningRecord.A_PRACTICE), event(2, 11, LearningRecord.A_PRACTICE)));
        service.correctExam(1L, 10L, false, Map.of(9L, -0.5), Map.of(9L, 0.2));
        assertEquals(0.6 - alpha * (1 - alpha) * 0.5, state.getMastery().doubleValue(), 0.0001);
        assertEquals(new BigDecimal("0.7"), state.getLastScoreRate());
    }

    @Test void laterDiagnosisSupersedesOldPractice() {
        when(records.selectList(any())).thenReturn(List.of(
                event(1, 10, LearningRecord.A_PRACTICE), event(2, 11, LearningRecord.A_DIAGNOSIS)));
        service.correctExam(1L, 10L, false, Map.of(9L, -0.5), Map.of(9L, 0.2));
        assertEquals(0.6, state.getMastery().doubleValue(), 1e-9);
    }

    @Test void resetClearsConfidenceAndPeak() {
        service.resetMastery(1L, List.of(9L));
        assertEquals(0, state.getPracticeCount());
        assertEquals(BigDecimal.ZERO, state.getMasteryPeak());
        assertNull(state.getLastPracticedAt()); assertNull(state.getLastScoreRate());
    }
    @Test void manualTeacherOverrideSupersedesOldExamObservation() {
        var override = event(2, 9, LearningRecord.A_ADJUST); override.setRefType("NODE");
        when(records.selectList(any())).thenReturn(List.of(event(1, 10, LearningRecord.A_PRACTICE), override));
        service.correctExam(1L, 10L, false, Map.of(9L, -0.5), Map.of(9L, 0.2));
        assertEquals(0.6, state.getMastery().doubleValue(), 1e-9);
    }

    private LearningRecord event(long id, long examId, String action) {
        var r = new LearningRecord(); r.setId(id); r.setNodeId(9L); r.setRefId(examId); r.setAction(action); r.setRefType("EXAM");
        return r;
    }
}
