package com.invoice_reader.invoice_reader.vente.service;

import com.invoice_reader.invoice_reader.achat.entity.AchatTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenteAlphaAgentExtractionService {

    private final VenteFieldExtractorService salesFieldExtractorService;

    public VenteExtractionResult extract(String ocrText, AchatTemplate template) {
        VenteExtractionResult heuristicsResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);
        VenteExtractionResult templateResult = template != null
                ? salesFieldExtractorService.extractWithTemplate(ocrText, template)
                : null;

        VenteExtractionResult selected = chooseBestResult(heuristicsResult, templateResult);
        if (selected == null) {
            return VenteExtractionResult.builder()
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

    private VenteExtractionResult chooseBestResult(
            VenteExtractionResult heuristicsResult,
            VenteExtractionResult templateResult) {
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
