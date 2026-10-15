package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.entity.RecommendRule;
import com.kgquiz.quiz.mapper.RecommendRuleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendServiceTest {
    private final RecommendRuleMapper rules = mock(RecommendRuleMapper.class);
    private final RecommendService service = new RecommendService(rules, null, null, null, null, null);
    @AfterEach void clear() { UserContext.clear(); }

    @Test void quotaPreservesStrategyOrderEvenWhenScoresDiffer() {
        var input = List.of(rec(1, null, "WEAK", 0.3), rec(2, null, "WEAK", 0.9));
        assertEquals(input, service.quota(input, RecommendService.Config.defaults(), 2));
    }
    @Test void weakReserveCannotBeDisplacedByOtherTiers() {
        var input = List.of(rec(1, 5L, "WEAK", 0.9), rec(2, 5L, "WEAK", 0.8),
                rec(3, 5L, "WEAK", 0.7), rec(4, null, "MID", 0.6), rec(5, null, "SOLID", 0.5));
        var cfg = service.sanitize(Map.of("maxPerParent", 1, "weakQuotaRatio", 0.75), RecommendService.Strategy.BALANCED);
        var result = service.quota(input, cfg, 4);
        assertEquals(3, result.stream().filter(r -> "WEAK".equals(r.tier())).count());
        assertEquals(4, result.size());
    }
    @Test void recentRepeatWindowCanBeDisabledAndConfigIsBounded() {
        var cfg = service.sanitize(Map.of("recentRepeatWindowDays", 0, "masteryWeight", 10,
                "dailyTaskLimit", 10000, "weakThreshold", 0.9, "solidThreshold", 0.2,
                "forgettingWeight", "NaN"), RecommendService.Strategy.BALANCED);
        assertEquals(0, cfg.recentRepeatWindowDays()); assertEquals(1, cfg.masteryWeight());
        assertEquals(20, cfg.dailyTaskLimit()); assertTrue(cfg.solidThreshold() > cfg.weakThreshold());
        assertTrue(Double.isFinite(cfg.forgettingWeight()));
    }
    @Test void sequentialPlanUsesLimitedSlotsForUnmasteredPrerequisitesFirst() {
        var prerequisite = rec(1, null, "MID", 0.3);
        var dependent = rec(2, null, "WEAK", 0.9);
        var result = service.includePrerequisites(List.of(dependent), List.of(dependent, prerequisite),
                Map.of(2L, List.of(1L)), Map.of(), 0.7, 1);
        assertEquals(List.of(prerequisite), result);
    }
    @Test void cyclicGraphDoesNotLoopOrDuplicateRecommendations() {
        var first = rec(1, null, "WEAK", 0.9); var second = rec(2, null, "WEAK", 0.8);
        var result = service.includePrerequisites(List.of(first, second), List.of(first, second),
                Map.of(1L, List.of(2L), 2L, List.of(1L)), Map.of(), 0.7, 2);
        assertEquals(2, result.size()); assertEquals(2, result.stream().map(RecommendService.Recommendation::nodeId).distinct().count());
    }
    @Test void updatingPlatformRuleIsForbiddenToOrganizationAdmin() {
        UserContext.set(new CurrentUser(1L, "test", UserRole.ORG_ADMIN, "Test", null, 1L, null, List.of()));
        RecommendRule row = new RecommendRule(); row.setId(2L); row.setOrgId(null);
        when(rules.selectById(2L)).thenReturn(row);
        assertThrows(ForbiddenException.class, () -> service.saveRule(2L, null, "Edited", "BALANCED", Map.of(), true, true));
        verify(rules, never()).updateById(any(RecommendRule.class));
    }
    @Test void explicitForeignOrganizationCannotBypassListScope() {
        UserContext.set(new CurrentUser(1L, "test", UserRole.TEACHER, "Test", null, 1L, 2L, List.of(3L)));
        assertThrows(ForbiddenException.class, () -> service.listRules(9L)); verifyNoInteractions(rules);
    }
    private RecommendService.Recommendation rec(long id, Long parent, String tier, double score) {
        return new RecommendService.Recommendation(id, "node" + id, "", parent, BigDecimal.ZERO,
                0, 1, 0.5, 0, "WEAK", "薄弱", BigDecimal.ONE, tier, score, score, List.of(), List.of(), "PRACTICE");
    }
}
