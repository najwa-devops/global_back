package com.invoice_reader.invoice_reader.servises.dynamic;

import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlphaAgentExtractionService {

    private final DynamicFieldExtractorService dynamicFieldExtractorService;

    public DynamicExtractionResult extract(String ocrText, DynamicTemplate template) {
        DynamicExtractionResult heuristicsResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
        DynamicExtractionResult templateResult = template != null
                ? dynamicFieldExtractorService.extractWithTemplate(ocrText, template)
                : null;

        DynamicExtractionResult selected = mergeBestResult(heuristicsResult, templateResult);
        if (selected == null) {
            return DynamicExtractionResult.builder()
                    .complete(false)
                    .overallConfidence(0.0)
                    .build();
        }

        selected.setLowConfidenceFields(selected.getLowConfidenceFields() != null
                ? new ArrayList<>(new LinkedHashSet<>(selected.getLowConfidenceFields()))
                : new ArrayList<>());
        selected.setMissingFields(selected.getMissingFields() != null
                ? new ArrayList<>(new LinkedHashSet<>(selected.getMissingFields()))
                : new ArrayList<>());

        log.info("AlphaAgentExtraction: selected={} extracted={} confidence={}",
                templateResult != null ? "template+alpha-merge" : "alpha-heuristics",
                selected.getExtractedCount(),
                selected.getOverallConfidence());
        return selected;
    }

    private DynamicExtractionResult mergeBestResult(
            DynamicExtractionResult heuristicsResult,
            DynamicExtractionResult templateResult) {
        if (templateResult == null) {
            return heuristicsResult;
        }
        if (heuristicsResult == null) {
            return templateResult;
        }

        Map<String, DynamicExtractionResult.ExtractedField> mergedFields = new LinkedHashMap<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        if (heuristicsResult.getExtractedFields() != null) {
            fieldNames.addAll(heuristicsResult.getExtractedFields().keySet());
        }
        if (templateResult.getExtractedFields() != null) {
            fieldNames.addAll(templateResult.getExtractedFields().keySet());
        }

        for (String fieldName : fieldNames) {
            DynamicExtractionResult.ExtractedField heuristicField =
                    heuristicsResult.getExtractedFields() != null
                            ? heuristicsResult.getExtractedFields().get(fieldName)
                            : null;
            DynamicExtractionResult.ExtractedField templateField =
                    templateResult.getExtractedFields() != null
                            ? templateResult.getExtractedFields().get(fieldName)
                            : null;

            DynamicExtractionResult.ExtractedField chosen = chooseField(fieldName, heuristicField, templateField);
            if (chosen != null) {
                mergedFields.put(fieldName, chosen);
            }
        }

        DynamicExtractionResult merged = DynamicExtractionResult.builder()
                .templateId(templateResult.getTemplateId() != null ? templateResult.getTemplateId() : heuristicsResult.getTemplateId())
                .templateName(templateResult.getTemplateName() != null ? templateResult.getTemplateName() : heuristicsResult.getTemplateName())
                .extractedFields(mergedFields)
                .missingFields(mergeLists(heuristicsResult.getMissingFields(), templateResult.getMissingFields()))
                .lowConfidenceFields(mergeLists(heuristicsResult.getLowConfidenceFields(), templateResult.getLowConfidenceFields()))
                .extractionDurationMs(
                        safeLong(heuristicsResult.getExtractionDurationMs())
                                + safeLong(templateResult.getExtractionDurationMs()))
                .build();

        merged.setComplete(merged.getMissingFields() == null || merged.getMissingFields().isEmpty());
        merged.setOverallConfidence(computeOverallConfidence(mergedFields));
        return merged;
    }

    private DynamicExtractionResult.ExtractedField chooseField(
            String fieldName,
            DynamicExtractionResult.ExtractedField heuristicField,
            DynamicExtractionResult.ExtractedField templateField) {
        if (heuristicField == null) {
            return templateField;
        }
        if (templateField == null) {
            return heuristicField;
        }

        boolean heuristicValidated = Boolean.TRUE.equals(heuristicField.getValidated());
        boolean templateValidated = Boolean.TRUE.equals(templateField.getValidated());
        if (heuristicValidated && !templateValidated) {
            return heuristicField;
        }
        if (templateValidated && !heuristicValidated) {
            return templateField;
        }

        double heuristicConfidence = heuristicField.getConfidence() != null ? heuristicField.getConfidence() : 0.0;
        double templateConfidence = templateField.getConfidence() != null ? templateField.getConfidence() : 0.0;

        if (sameValue(heuristicField.getNormalizedValue(), templateField.getNormalizedValue())) {
            return heuristicConfidence >= templateConfidence ? heuristicField : templateField;
        }

        if (isCoreAmountField(fieldName)) {
            double heuristicBoost = amountFieldBoost(fieldName, heuristicField);
            double templateBoost = amountFieldBoost(fieldName, templateField);
            return heuristicBoost >= templateBoost ? heuristicField : templateField;
        }

        return heuristicConfidence >= templateConfidence ? heuristicField : templateField;
    }

    private boolean isCoreAmountField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return "amountHT".equals(fieldName)
                || "tva".equals(fieldName)
                || "tva2".equals(fieldName)
                || "amountTTC".equals(fieldName);
    }

    private double amountFieldBoost(String fieldName, DynamicExtractionResult.ExtractedField field) {
        if (field == null) {
            return 0.0;
        }
        double score = field.getConfidence() != null ? field.getConfidence() : 0.0;
        if (Boolean.TRUE.equals(field.getValidated())) {
            score += 0.15;
        }
        Object value = field.getNormalizedValue() != null ? field.getNormalizedValue() : field.getValue();
        if (value != null && String.valueOf(value).contains(".")) {
            score += 0.02;
        }
        if ("amountTTC".equals(fieldName) && value != null && !String.valueOf(value).isBlank()) {
            score += 0.05;
        }
        return score;
    }

    private boolean sameValue(Object left, Object right) {
        String normalizedLeft = normalizeComparable(left);
        String normalizedRight = normalizeComparable(right);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
    }

    private String normalizeComparable(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .trim()
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private double computeOverallConfidence(Map<String, DynamicExtractionResult.ExtractedField> fields) {
        if (fields == null || fields.isEmpty()) {
            return 0.0;
        }

        double total = 0.0;
        int count = 0;
        for (DynamicExtractionResult.ExtractedField field : fields.values()) {
            if (field == null) {
                continue;
            }
            total += field.getConfidence() != null ? field.getConfidence() : 0.0;
            count++;
        }

        return count > 0 ? total / count : 0.0;
    }

    private <T> ArrayList<T> mergeLists(java.util.List<T> first, java.util.List<T> second) {
        LinkedHashSet<T> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return new ArrayList<>(merged);
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }
}
