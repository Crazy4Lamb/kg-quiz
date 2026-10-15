package com.kgquiz.quiz.storyboard;

import com.kgquiz.quiz.entity.Question;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StoryboardCheckerTest {
    @Test void decimalPointIsNotDiscardedDuringAnswerComparison() {
        assertEquals(false, check("答案：25", "2.5").get("passed"));
    }
    @Test void substringOfAnswerIsNotAccepted() {
        assertEquals(false, check("答案：2", "12").get("passed"));
    }
    @Test void colonAndSentencePunctuationAreAccepted() {
        assertEquals(true, check("答案：2.5。", "2.5").get("passed"));
    }
    @Test void eachVisualUsesItsOwnQuestionInMultiQuestionSegments() {
        var segment = segment(); segment.setQuestionIds(List.of(1L, 2L));
        segment.setVisuals(List.of(Storyboard.Visual.question("答案：2", 1L), Storyboard.Visual.question("答案：7", 2L)));
        var report = StoryboardChecker.check(new Storyboard().seg(segment), Map.of(1L, question("2"), 2L, question("7")));
        assertEquals(true, report.get("passed"));
    }
    @Test void legacyUnboundMultiQuestionVisualIsNotComparedToEveryAnswer() {
        var segment = segment(); segment.setQuestionIds(List.of(1L, 2L));
        segment.setVisuals(List.of(Storyboard.Visual.text("答案：2")));
        assertDoesNotThrow(() -> StoryboardChecker.check(new Storyboard().seg(segment), Map.of(1L, question("2"), 2L, question("7"))));
    }
    private Map<String, Object> check(String visual, String answer) {
        var s = segment(); s.setQuestionIds(List.of(1L)); s.setVisuals(List.of(Storyboard.Visual.question(visual, 1L)));
        return StoryboardChecker.check(new Storyboard().seg(s), Map.of(1L, question(answer)));
    }
    private Storyboard.Segment segment() {
        var s = new Storyboard.Segment(); s.setIndex(1); s.setKind("EXAMPLE"); s.setNarration("讲解这一道例题。"); return s;
    }
    private Question question(String answer) { var q = new Question(); q.setAnswer(answer); return q; }
}
