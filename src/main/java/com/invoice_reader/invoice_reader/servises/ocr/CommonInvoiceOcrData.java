package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.dto.ocr.OcrResult;

import java.util.LinkedHashMap;
import java.util.Map;

public record CommonInvoiceOcrData(
        OcrResult ocrResult,
        String rawText,
        String cleanedText,
        boolean scanned,
        DocumentQualityAssessment qualityAssessment
) {
    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ocrResult != null) {
            metadata.put("ocrConfidence", ocrResult.getConfidence());
            metadata.put("ocrProcessingTimeMs", ocrResult.getProcessingTimeMs());
            metadata.put("ocrAttemptNumber", ocrResult.getAttemptNumber());
            metadata.put("ocrImageWidth", ocrResult.getImageWidth());
            metadata.put("ocrImageHeight", ocrResult.getImageHeight());
            metadata.put("ocrTelemetry", ocrResult.getTelemetry());
            Object engineUsed = ocrResult.getTelemetry() != null ? ocrResult.getTelemetry().get("ocrEngineUsed") : null;
            if (engineUsed != null) {
                metadata.put("ocrEngine", engineUsed);
                metadata.put("ocrEngineActive", engineUsed);
            }
        }
        metadata.put("scanned", scanned);
        if (qualityAssessment != null) {
            metadata.putAll(qualityAssessment.toMap());
        }
        return metadata;
    }
}
