package com.invoice_reader.invoice_reader.sales.service;

import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class SalesAlphaAgentExtractionService {

    private final SalesFieldExtractorService salesFieldExtractorService;

    public SalesExtractionResult extract(String ocrText, DynamicTemplate template) {
        SalesExtractionResult heuristicsResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);
        SalesExtractionResult templateResult = template != null
                ? salesFieldExtractorService.extractWithTemplate(ocrText, template)
                : null;

        SalesExtractionResult selected = chooseBestResult(heuristicsResult, templateResult);
        if (selected == null) {
            return SalesExtractionResult.builder()
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

        log.info("SalesAlphaAgentExtraction: selected={} extracted={} confidence={}",
                selected == templateResult ? "template+alpha" : "alpha-heuristics",
                selected.getExtractedCount(),
                selected.getOverallConfidence());
        return selected;
    }

    private SalesExtractionResult chooseBestResult(
            SalesExtractionResult heuristicsResult,
            SalesExtractionResult templateResult) {
        if (templateResult == null) {
            return heuristicsResult;
        }
        if (heuristicsResult == null) {
            return templateResult;
        }

        int templateExtracted = templateResult.getExtractedCount();
        int heuristicExtracted = heuristicsResult.getExtractedCount();
        if (templateExtracted > heuristicExtracted) {
            return templateResult;
        }
        if (heuristicExtracted > templateExtracted) {
            return heuristicsResult;
        }

        double templateConfidence = templateResult.getOverallConfidence() != null
                ? templateResult.getOverallConfidence()
                : 0.0;
        double heuristicConfidence = heuristicsResult.getOverallConfidence() != null
                ? heuristicsResult.getOverallConfidence()
                : 0.0;
        return templateConfidence >= heuristicConfidence ? templateResult : heuristicsResult;
    }
}
