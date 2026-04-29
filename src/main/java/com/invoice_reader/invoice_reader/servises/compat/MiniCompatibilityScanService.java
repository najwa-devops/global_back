package com.invoice_reader.invoice_reader.servises.compat;

import com.invoice_reader.invoice_reader.dto.ocr.OcrResult;
import com.invoice_reader.invoice_reader.sales.service.SalesExtractionResult;
import com.invoice_reader.invoice_reader.sales.service.SalesFieldExtractorService;
import com.invoice_reader.invoice_reader.servises.dynamic.DynamicExtractionResult;
import com.invoice_reader.invoice_reader.servises.dynamic.DynamicFieldExtractorService;
import com.invoice_reader.invoice_reader.servises.ocr.CommonInvoiceOcrData;
import com.invoice_reader.invoice_reader.servises.ocr.CommonInvoiceOcrService;
import com.invoice_reader.invoice_reader.servises.ocr.ImagePreprocessingService;
import com.invoice_reader.invoice_reader.servises.ocr.PaddleOcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class    MiniCompatibilityScanService {

    private static final int ALPHA_PDF_DPI = 240;
    private static final int ALPHA_A4_PDF_DPI = 320;
    private static final int ALPHA_MAX_DIMENSION = 1600;
    private static final int ALPHA_NATIVE_MIN_CHARS = 100;
    private static final int ALPHA_MAX_PAGES = 4;

    private final CommonInvoiceOcrService commonInvoiceOcrService;
    private final DynamicFieldExtractorService dynamicFieldExtractorService;
    private final SalesFieldExtractorService salesFieldExtractorService;
    private final PaddleOcrService paddleOcrService;
    private final ImagePreprocessingService imagePreprocessingService;

    public OcrPayload extractPurchaseOcrOnly(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-ocr-only-");
        try {
            CommonInvoiceOcrData ocrData = commonInvoiceOcrService.analyze(tempFile);
            String raw = ocrData.rawText() == null ? "" : ocrData.rawText();
            String cleaned = getBestText(ocrData);
            return new OcrPayload(raw, cleaned, ocrData.scanned(), ocrData.toMetadataMap());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public OcrPayload extractPurchaseAlphaOcrOnly(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-alpha-ocr-only-");
        try {
            AlphaOcrPayload alphaPayload = extractAlphaPayload(tempFile, file.getOriginalFilename());
            String raw = alphaPayload.rawOcrText() == null ? "" : alphaPayload.rawOcrText();
            return new OcrPayload(raw, raw, true, alphaPayload.telemetry());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public List<Map<String, Object>> scanPurchase(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-scan-");
        try {
            CommonInvoiceOcrData ocrData = commonInvoiceOcrService.analyze(tempFile);
            String ocrText = getBestText(ocrData);
            DynamicExtractionResult extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);

            List<Map<String, Object>> response = new ArrayList<>(extractionResult.toSimpleMap().entrySet().stream()
                    .map(entry -> fieldValue(entry.getKey(), entry.getValue()))
                    .toList());
            response.add(fieldValue("rawOcrText", ocrData.rawText()));
            response.add(fieldValue("extractedText", ocrText));
            response.add(fieldValue("fieldDiagnostics", extractionResult.toDetailedMap()));
            response.add(fieldValue("ocrTelemetry", ocrData.toMetadataMap()));
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public List<Map<String, Object>> scanSales(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "vente-scan-");
        try {
            CommonInvoiceOcrData ocrData = commonInvoiceOcrService.analyze(tempFile);
            String ocrText = getBestText(ocrData);
            SalesExtractionResult extractionResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);

            List<Map<String, Object>> response = new ArrayList<>(extractionResult.toSimpleMap().entrySet().stream()
                    .map(entry -> fieldValue(entry.getKey(), entry.getValue()))
                    .toList());
            response.add(fieldValue("rawOcrText", ocrData.rawText()));
            response.add(fieldValue("extractedText", ocrText));
            response.add(fieldValue("fieldDiagnostics", extractionResult.toDetailedMap()));
            response.add(fieldValue("ocrTelemetry", ocrData.toMetadataMap()));
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public List<Map<String, Object>> scanPurchaseAlpha(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-alpha-scan-");
        try {
            AlphaOcrPayload alphaPayload = extractAlphaPayload(tempFile, file.getOriginalFilename());
            String ocrText = alphaPayload.rawOcrText();
            DynamicExtractionResult extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);

            List<Map<String, Object>> response = new ArrayList<>(extractionResult.toSimpleMap().entrySet().stream()
                    .map(entry -> fieldValue(entry.getKey(), entry.getValue()))
                    .toList());
            response.add(fieldValue("rawOcrText", ocrText));
            response.add(fieldValue("extractedText", ocrText));
            response.add(fieldValue("fieldDiagnostics", extractionResult.toDetailedMap()));
            response.add(fieldValue("ocrTelemetry", alphaPayload.telemetry()));
            response.add(fieldValue("extractionMethod", "ALPHA_AGENT"));
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public List<Map<String, Object>> scanSalesAlpha(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "vente-alpha-scan-");
        try {
            AlphaOcrPayload alphaPayload = extractAlphaPayload(tempFile, file.getOriginalFilename());
            String ocrText = alphaPayload.rawOcrText();
            SalesExtractionResult extractionResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);

            List<Map<String, Object>> response = new ArrayList<>(extractionResult.toSimpleMap().entrySet().stream()
                    .map(entry -> fieldValue(entry.getKey(), entry.getValue()))
                    .toList());
            response.add(fieldValue("rawOcrText", ocrText));
            response.add(fieldValue("extractedText", ocrText));
            response.add(fieldValue("fieldDiagnostics", extractionResult.toDetailedMap()));
            response.add(fieldValue("ocrTelemetry", alphaPayload.telemetry()));
            response.add(fieldValue("extractionMethod", "ALPHA_AGENT"));
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public List<Map<String, Object>> alphaPureOcr(MultipartFile file) throws Exception {
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-alpha-");
        try {
            AlphaOcrPayload alphaPayload = extractAlphaPayload(tempFile, file.getOriginalFilename());
            String rawOcrText = alphaPayload.rawOcrText();
            Map<String, Object> telemetry = alphaPayload.telemetry();

            List<Map<String, Object>> response = new ArrayList<>();
            response.add(fieldValue("rawOcrText", rawOcrText));
            response.add(fieldValue("extractedText", rawOcrText));
            response.add(fieldValue("ocrTelemetry", telemetry));
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private AlphaOcrPayload extractAlphaPayload(Path tempFile, String originalFilename) throws Exception {
        String originalName = originalFilename != null ? originalFilename.toLowerCase() : "";
        String rawOcrText;
        Map<String, Object> telemetry = new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();

        if (originalName.endsWith(".pdf")) {
            rawOcrText = extractAlphaPdf(tempFile, telemetry);
        } else {
            rawOcrText = extractAlphaImage(tempFile, telemetry);
        }

        telemetry.put("totalAlphaTimeMs", System.currentTimeMillis() - startedAt);
        telemetry.put("textLength", rawOcrText.length());
        telemetry.put("alphaRequested", true);
        return new AlphaOcrPayload(rawOcrText, telemetry);
    }

    private record AlphaOcrPayload(String rawOcrText, Map<String, Object> telemetry) {
    }

    public record OcrPayload(
            String rawText,
            String cleanedText,
            boolean scanned,
            Map<String, Object> telemetry
    ) {
    }

    private String extractAlphaPdf(Path pdfPath, Map<String, Object> telemetry) throws Exception {
        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, ALPHA_MAX_PAGES);

            // Try native text extraction for ALL pages first
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToProcess);
            String nativeText = stripper.getText(document).trim();

            if (nativeText.length() >= ALPHA_NATIVE_MIN_CHARS) {
                telemetry.put("alphaStrategy", "native-pdf-text");
                telemetry.put("nativeTextLength", nativeText.length());
                telemetry.put("pagesProcessed", pagesToProcess);
                return nativeText;
            }

            // Native text insufficient → OCR each page at high DPI and combine
            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder combined = new StringBuilder();
            telemetry.put("alphaStrategy", "paddle-ocr-pdf-multipage");

            for (int i = 0; i < pagesToProcess; i++) {
                int dpi = determineAlphaPdfDpi(document, i);
                telemetry.put("pdfRenderDpiPage" + (i + 1), dpi);
                BufferedImage pageImage = renderer.renderImageWithDPI(i, dpi);
                String pageText = runAlphaPageOcr(pageImage, i == 0 ? telemetry : new LinkedHashMap<>(),
                        "pdf-page-" + (i + 1));
                if (!pageText.isBlank()) {
                    if (combined.length() > 0) combined.append("\n[PAGE ").append(i + 2).append("]\n");
                    combined.append(pageText.trim());
                }
                // Stop early if we have enough text from first page
                if (i == 0 && combined.length() > 800) break;
            }

            telemetry.put("pagesProcessed", pagesToProcess);
            return combined.toString();
        }
    }

    private String extractAlphaImage(Path imagePath, Map<String, Object> telemetry) throws Exception {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            telemetry.put("alphaStrategy", "image-read-failed");
            return "";
        }
        telemetry.put("alphaStrategy", "paddle-ocr-image");
        return runAlphaImageVariants(image, telemetry, "image");
    }

    private String runAlphaPageOcr(BufferedImage image, Map<String, Object> telemetry, String stage) {
        List<BufferedImage> variants = buildAlphaVariants(image);
        String bestText = "";
        double bestScore = -1.0;
        int index = 0;
        for (BufferedImage variant : variants) {
            OcrResult result = paddleOcrService.extractHighQuality(variant, stage + "_" + index);
            String text = result.getText() != null ? result.getText() : "";
            double score = scoreAlphaText(text, result.getConfidence());
            if (score > bestScore) {
                bestScore = score;
                bestText = text;
            }
            telemetry.put(stage + "_variant_" + index + "_success", result.isSuccess());
            telemetry.put(stage + "_variant_" + index + "_confidence", result.getConfidence());
            telemetry.put(stage + "_variant_" + index + "_length", text.length());
            index++;
        }
        telemetry.put(stage + "_bestLength", bestText.length());
        telemetry.put(stage + "_bestScore", bestScore);
        return bestText;
    }

    private String runAlphaImageVariants(BufferedImage image, Map<String, Object> telemetry, String stage) {
        List<BufferedImage> variants = buildAlphaVariants(image);
        String bestText = "";
        double bestScore = -1.0;
        int index = 0;
        for (BufferedImage variant : variants) {
            OcrResult result = paddleOcrService.extractHighQuality(variant, stage + "_" + index);
            String text = result.getText() != null ? result.getText() : "";
            double score = scoreAlphaText(text, result.getConfidence());
            if (score > bestScore) {
                bestScore = score;
                bestText = text;
            }
            telemetry.put(stage + "_variant_" + index + "_success", result.isSuccess());
            telemetry.put(stage + "_variant_" + index + "_confidence", result.getConfidence());
            telemetry.put(stage + "_variant_" + index + "_length", text.length());
            index++;
        }
        telemetry.put(stage + "_bestLength", bestText.length());
        telemetry.put(stage + "_bestScore", bestScore);
        return bestText;
    }

    private List<BufferedImage> buildAlphaVariants(BufferedImage image) {
        List<BufferedImage> variants = new ArrayList<>();
        if (image == null) {
            return variants;
        }

        variants.add(prepareForOcr(image));

        try {
            BufferedImage lab = matToBuffered(imagePreprocessingService.labColorSpacePipeline(
                    imagePreprocessingService.bufferedImageToMat(image)));
            variants.add(prepareForOcr(lab));
        } catch (Exception e) {
            log.debug("Alpha LAB variant skipped: {}", e.getMessage());
        }

        try {
            BufferedImage standard = matToBuffered(imagePreprocessingService.standardPipeline(
                    imagePreprocessingService.bufferedImageToMat(image)));
            variants.add(prepareForOcr(standard));
        } catch (Exception e) {
            log.debug("Alpha standard variant skipped: {}", e.getMessage());
        }

        try {
            BufferedImage despeckle = matToBuffered(imagePreprocessingService.despeckleInvoicePipeline(
                    imagePreprocessingService.bufferedImageToMat(image)));
            variants.add(prepareForOcr(despeckle));
        } catch (Exception e) {
            log.debug("Alpha despeckle variant skipped: {}", e.getMessage());
        }

        return deduplicateVariants(variants);
    }

    private List<BufferedImage> deduplicateVariants(List<BufferedImage> variants) {
        List<BufferedImage> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BufferedImage variant : variants) {
            if (variant == null) {
                continue;
            }
            String signature = variant.getWidth() + "x" + variant.getHeight() + ":" + variant.getType();
            if (seen.add(signature)) {
                unique.add(variant);
            }
        }
        return unique;
    }

    private double scoreAlphaText(String text, double confidence) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        double score = confidence * 0.55;
        score += Math.min(text.length(), 1800) / 25.0;
        String upper = text.toUpperCase();
        if (upper.contains("FACTURE") || upper.contains("INVOICE")) {
            score += 10;
        }
        if (upper.contains("TOTAL")) {
            score += 8;
        }
        if (upper.contains("TVA") || upper.contains("TAX")) {
            score += 6;
        }
        if (upper.contains("ICE") || upper.contains("IF") || upper.contains("RC")) {
            score += 6;
        }
        return score;
    }

    private BufferedImage prepareForOcr(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        // Upscale small images to at least 1200px on the long side (helps with small text)
        int minDim = 1200;
        if (w < minDim && h < minDim) {
            double scale = (double) minDim / Math.max(w, h);
            w = (int) (w * scale);
            h = (int) (h * scale);
        }

        // Cap at max dimension to avoid excessive memory use
        if (w > ALPHA_MAX_DIMENSION || h > ALPHA_MAX_DIMENSION) {
            double scale = Math.min((double) ALPHA_MAX_DIMENSION / w, (double) ALPHA_MAX_DIMENSION / h);
            w = Math.max(1, (int) (w * scale));
            h = Math.max(1, (int) (h * scale));
        }

        if (w == image.getWidth() && h == image.getHeight()
                && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private BufferedImage matToBuffered(org.opencv.core.Mat mat) throws Exception {
        return imagePreprocessingService.matToBufferedImage(mat);
    }

    private int determineAlphaPdfDpi(PDDocument document, int pageIndex) {
        try {
            if (document != null && pageIndex >= 0 && pageIndex < document.getNumberOfPages()) {
                PDPage page = document.getPage(pageIndex);
                PDRectangle mediaBox = page.getMediaBox();
                if (isA4Like(mediaBox)) {
                    return ALPHA_A4_PDF_DPI;
                }
            }
        } catch (Exception e) {
            log.debug("Alpha A4 DPI fallback ignore: {}", e.getMessage());
        }
        return ALPHA_PDF_DPI;
    }

    private boolean isA4Like(PDRectangle mediaBox) {
        if (mediaBox == null) {
            return false;
        }
        float width = mediaBox.getWidth();
        float height = mediaBox.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        double ratio = Math.max(width, height) / Math.min(width, height);
        return ratio >= 1.30 && ratio <= 1.55;
    }

    public Map<String, Object> extractPurchaseField(MultipartFile file, String fieldName) throws Exception {
        String normalizedFieldName = validateFieldName(fieldName);
        validateFile(file);
        Path tempFile = createTempFile(file, "invoice-scan-field-");
        try {
            CommonInvoiceOcrData ocrData = commonInvoiceOcrService.analyze(tempFile);
            String ocrText = getBestText(ocrData);
            DynamicExtractionResult extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
            Object extractedValue = extractionResult.toSimpleMap().get(normalizedFieldName);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("field", normalizedFieldName);
            response.put("value", extractedValue == null ? "" : extractedValue);
            response.put("rawOcrText", ocrData.rawText() == null ? "" : ocrData.rawText());
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public Map<String, Object> extractSalesField(MultipartFile file, String fieldName) throws Exception {
        String normalizedFieldName = validateFieldName(fieldName);
        validateFile(file);
        Path tempFile = createTempFile(file, "vente-scan-field-");
        try {
            CommonInvoiceOcrData ocrData = commonInvoiceOcrService.analyze(tempFile);
            String ocrText = getBestText(ocrData);
            SalesExtractionResult extractionResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);
            Object extractedValue = extractionResult.toSimpleMap().get(normalizedFieldName);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("field", normalizedFieldName);
            response.put("value", extractedValue == null ? "" : extractedValue);
            response.put("rawOcrText", ocrData.rawText() == null ? "" : ocrData.rawText());
            return response;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Map<String, Object> fieldValue(String field, Object value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("value", value == null ? "" : value);
        return item;
    }

    private String getBestText(CommonInvoiceOcrData ocrData) {
        if (ocrData == null) {
            return "";
        }
        if (ocrData.cleanedText() != null && !ocrData.cleanedText().isBlank()) {
            return ocrData.cleanedText();
        }
        return ocrData.rawText() == null ? "" : ocrData.rawText();
    }

    private Path createTempFile(MultipartFile file, String prefix) throws Exception {
        String originalName = file.getOriginalFilename();
        String suffix = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.'))
                : ".tmp";

        Path tempFile = Files.createTempFile(prefix, suffix);
        file.transferTo(tempFile);
        return tempFile;
    }

    private String validateFieldName(String fieldName) {
        String normalizedFieldName = fieldName == null ? "" : fieldName.trim();
        if (normalizedFieldName.isBlank()) {
            throw new IllegalArgumentException("Missing field name");
        }
        return normalizedFieldName;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Empty file");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("Missing file name");
        }

        String lower = originalName.toLowerCase();
        if (!(lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
            throw new IllegalArgumentException("Unsupported file type");
        }
    }
}
