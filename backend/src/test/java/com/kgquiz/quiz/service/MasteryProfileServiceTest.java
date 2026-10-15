package com.kgquiz.quiz.service;

import com.kgquiz.quiz.entity.StudentNodeState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasteryProfileServiceTest {
    private final MasteryService mastery = mock(MasteryService.class);
    private final MasteryProfileService service = new MasteryProfileService(mastery, null, null, mock(StudentAccessService.class));
    @Test void decayNeverRaisesCorrectedMasteryAboveMeasuredValue() {
        var st = state(0.2, 0.9); when(mastery.stateOf(1L, 2L)).thenReturn(st);
        var p = service.profile(1L, 2L, RecommendService.Config.defaults());
        assertTrue(p.decayed() <= p.raw()); assertTrue(p.delta() >= 0);
    }
    @Test void reportMasteredBoundaryMatchesRecommendationSolidBoundary() {
        var st = state(0.75, 0.75); when(mastery.stateOf(1L, 2L)).thenReturn(st);
        var p = service.profile(1L, 2L, RecommendService.Config.defaults());
        assertEquals(MasteryProfileService.Level.MASTERED, p.level());
    }
    @Test void futureTimestampsDoNotMakeObservedKnowledgeUnseen() {
        assertEquals(0, MasteryProfileService.daysSince(LocalDateTime.now().plusDays(3)));
    }
    private StudentNodeState state(double raw, double peak) {
        var s = new StudentNodeState(); s.setMastery(BigDecimal.valueOf(raw)); s.setMasteryPeak(BigDecimal.valueOf(peak));
        s.setPracticeCount(3); s.setLastPracticedAt(LocalDateTime.now()); return s;
    }
}
