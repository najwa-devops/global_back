package com.invoice_reader.invoice_reader.ocr.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DocumentQualityAssessment(
        int qualityScore,
        DocumentDifficultyClass difficultyClass,
        List<String> qualityFlags,
        Map<String, Object> metrics
) {
    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qualityScore", qualityScore);
        payload.put("difficultyClass", difficultyClass != null ? difficultyClass.name() : null);
        payload.put("qualityFlags", qualityFlags);
        payload.put("qualityMetrics", metrics);
        return payload;
    }
}
