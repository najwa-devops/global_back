package com.invoice_reader.invoice_reader.achat.dto;

import com.invoice_reader.invoice_reader.achat.service.AchatExtractionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DTO de réponse pour l'extraction dynamique
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AchatExtractionResponse {

    private boolean success;
    private String message;
    private Long invoiceId;
    private Long templateId;
    private String templateName;

    private Map<String, ExtractedFieldResponse> extractedFields;
    private List<String> missingFields;
    private List<String> lowConfidenceFields;

    private Double overallConfidence;
    private Integer extractedCount;
    private Integer totalFields;
    private Boolean isComplete;

    private Long extractionDurationMs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExtractedFieldResponse {
        private String value;
        private Object normalizedValue;
        private Double confidence;
        private String detectionMethod;
        private Boolean validated;
        private String validationError;
    }

    /**
     * Crée une réponse à partir d'un résultat d'extraction
     */
    public static AchatExtractionResponse fromResult(AchatExtractionResult result, Long invoiceId) {
        if (result == null) {
            return AchatExtractionResponse.builder()
                .success(false)
                .message("Résultat d'extraction null")
                .invoiceId(invoiceId)
                .build();
        }

        Map<String, ExtractedFieldResponse> fields = result.getExtractedFields().entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> ExtractedFieldResponse.builder()
                    .value(e.getValue().getValue())
                    .normalizedValue(e.getValue().getNormalizedValue())
                    .confidence(e.getValue().getConfidence())
                    .detectionMethod(e.getValue().getDetectionMethod())
                    .validated(e.getValue().getValidated())
                    .validationError(e.getValue().getValidationError())
                    .build()
            ));

        int totalFields = result.getExtractedCount() + result.getMissingCount();

        return AchatExtractionResponse.builder()
            .success(true)
            .message(result.isComplete() ? "Extraction complète" : "Extraction partielle - champs manquants")
            .invoiceId(invoiceId)
            .templateId(result.getTemplateId())
            .templateName(result.getTemplateName())
            .extractedFields(fields)
            .missingFields(result.getMissingFields())
            .lowConfidenceFields(result.getLowConfidenceFields())
            .overallConfidence(result.getOverallConfidence())
            .extractedCount(result.getExtractedCount())
            .totalFields(totalFields)
            .isComplete(result.isComplete())
            .extractionDurationMs(result.getExtractionDurationMs())
            .build();
    }

    /**
     * Crée une réponse d'erreur
     */
    public static AchatExtractionResponse error(String message, Long invoiceId) {
        return AchatExtractionResponse.builder()
            .success(false)
            .message(message)
            .invoiceId(invoiceId)
            .build();
    }
}
