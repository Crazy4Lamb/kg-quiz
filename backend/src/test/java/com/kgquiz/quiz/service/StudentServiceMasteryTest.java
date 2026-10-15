package com.kgquiz.quiz.service;

import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.StudentGraph;
import com.kgquiz.quiz.entity.StudentNodeState;
import com.kgquiz.quiz.mapper.StudentGraphMapper;
import com.kgquiz.quiz.mapper.StudentNodeStateMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.common.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceMasteryTest {
    @Mock StudentGraphMapper graphs;
    @Mock StudentNodeStateMapper states;
    @Mock GraphStore graphStore;
    @Mock StudentAccessService access;
    @Mock LearningRecordService records;
    @InjectMocks StudentService service;
    private GraphNode node;
    @BeforeEach void setup() {
        var sg = new StudentGraph(); sg.setId(5L); sg.setSourceGraphId(100L);
        when(graphs.selectOne(any())).thenReturn(sg);
        node = new GraphNode(); node.setId(9L); node.setGraphId(100L);
        when(graphStore.findNode(9L)).thenReturn(node);
    }
    @Test void manualInitialObservationCanBeShownInMasteryProfile() {
        service.adjustMastery(1L, 9L, new BigDecimal("0.8"));
        ArgumentCaptor<StudentNodeState> saved = ArgumentCaptor.forClass(StudentNodeState.class);
        verify(states).insert(saved.capture()); var state = saved.getValue();
        assertEquals(1, state.getPracticeCount()); assertNotNull(state.getLastPracticedAt());
        assertEquals(new BigDecimal("0.8000"), state.getMasteryPeak());
    }
    @Test void manualResetRemovesAllPreviousLearningEvidence() {
        var state = new StudentNodeState(); state.setId(8L); state.setPracticeCount(8);
        state.setMasteryPeak(new BigDecimal("0.9")); state.setMastery(new BigDecimal("0.7"));
        when(states.selectOne(any())).thenReturn(state);
        service.adjustMastery(1L, 9L, BigDecimal.ZERO);
        assertEquals(0, state.getPracticeCount()); assertEquals(BigDecimal.ZERO, state.getMasteryPeak());
        assertNull(state.getLastPracticedAt()); assertNull(state.getLastScoreRate());
    }
    @Test void teacherCannotInsertStateForNodeFromAnotherGraph() {
        node.setGraphId(200L);
        assertThrows(ForbiddenException.class, () -> service.adjustMastery(1L, 9L, new BigDecimal("0.8")));
        verifyNoInteractions(states);
    }
}
