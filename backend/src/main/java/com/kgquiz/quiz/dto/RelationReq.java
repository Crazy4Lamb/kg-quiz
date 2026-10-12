package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RelationReq(
        @NotNull Long fromNodeId,
        @NotNull Long toNodeId,
        @NotBlank String relationType) {
}
