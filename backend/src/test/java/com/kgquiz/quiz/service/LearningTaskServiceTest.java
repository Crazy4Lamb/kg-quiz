package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.entity.LearningTask;
import com.kgquiz.quiz.entity.Student;
import com.kgquiz.quiz.mapper.LearningTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningTaskServiceTest {
    @Mock LearningTaskMapper mapper;
    @Mock RecommendService recommend;
    @Mock DiagnosisService diagnosis;
    @Mock StudentAccessService access;
    @Mock com.kgquiz.quiz.mapper.NodeContentMapper contents;
    @Mock com.kgquiz.quiz.mapper.StudentGraphMapper studentGraphs;
    @Mock com.kgquiz.quiz.service.neo4j.GraphStore graph;
    @InjectMocks LearningTaskService service;
    @BeforeEach void login() {
        UserContext.set(new CurrentUser(1L, "test", UserRole.STUDENT, "Test", 1L, 2L, 3L, List.of()));
        Student s = new Student(); s.setId(1L); s.setOrgId(2L); s.setName("Test"); when(access.requireStudent(1L)).thenReturn(s);
    }
    @AfterEach void clear() { UserContext.clear(); }
    @Test void practiceRecommendationDoesNotCreateRedundantReadingTask() {
        when(diagnosis.completed(1L)).thenReturn(true);
        when(recommend.recommend(1L, null)).thenReturn(List.of(rec("PRACTICE")));
        service.generate(1L, false);
        ArgumentCaptor<LearningTask> task = ArgumentCaptor.forClass(LearningTask.class);
        verify(mapper).insert(task.capture()); assertEquals("PRACTICE", task.getValue().getType());
    }
    @Test void merelyHavingDiagnosisTaskDoesNotSkipUnfinishedAssessment() {
        when(diagnosis.completed(1L)).thenReturn(false);
        service.generate(1L, false);
        ArgumentCaptor<LearningTask> task = ArgumentCaptor.forClass(LearningTask.class);
        verify(mapper).insert(task.capture()); assertEquals("DIAGNOSIS", task.getValue().getType());
        verifyNoInteractions(recommend);
    }
    private RecommendService.Recommendation rec(String action) {
        return new RecommendService.Recommendation(9L, "node", "", null, BigDecimal.ZERO, 0, 1,
                0.1, 0, "WEAK", "薄弱", BigDecimal.ONE, "WEAK", 0.9, 0.9, List.of(), List.of(), action);
    }
}
