package com.kgquiz.quiz.dto;

import java.util.List;

public record PositionReq(List<PositionItem> items) {

    public record PositionItem(Long nodeId, Integer posX, Integer posY) {
    }
}
