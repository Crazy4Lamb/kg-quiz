package com.kgquiz.quiz.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GradeValidationTest {
    @Test void nestedNegativeScoresAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new GradeReq(List.of(new GradeReq.Item(1L, new BigDecimal("-1"), "")));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
    @Test void nestedQuestionIdentifierIsRequired() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new AssignmentSubmitReq(
                    List.of(new AssignmentSubmitReq.Item(null, "A")))).isEmpty());
        }
    }
}
