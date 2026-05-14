package com.invoice_reader.invoice_reader.ocr.service;

import com.invoice_reader.invoice_reader.ocr.dto.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceOcrService {

    private final AdvancedOcrService advancedOcrService;
    private final TextCleaningService textCleaningService;
    private final DocumentQualityScoringService documentQualityScoringService;

    public CommonInvoiceOcrData analyze(Path path) {
        OcrResult ocrResult = advancedOcrService.extractTextAdvanced(path);
        String rawText = ocrResult != null && ocrResult.getText() != null ? ocrResult.getText() : "";
        boolean scanned = isScannedDocument(path);
        String cleanedText = textCleaningService.clean(rawText);
        DocumentQualityAssessment qualityAssessment = documentQualityScoringService.assess(path, ocrResult, scanned);
        return new CommonInvoiceOcrData(ocrResult, rawText, cleanedText, scanned, qualityAssessment);
    }

    private boolean isScannedDocument(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String filename = path.getFileName().toString().toLowerCase();
        if (!filename.endsWith(".pdf")) {
            return false;
        }
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            String nativeText = new PDFTextStripper().getText(doc);
            return nativeText == null || nativeText.strip().length() < 50;
        } catch (Exception e) {
            log.debug("Lecture PDF native impossible, document considéré scanné: {}", e.getMessage());
            return true;
        }
    }
}
