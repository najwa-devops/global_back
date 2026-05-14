package com.invoice_reader.invoice_reader.ocr.service;

import com.invoice_reader.invoice_reader.ocr.dto.OcrResult;
import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.ParamConfig;
import io.github.hzkitty.entity.RecResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.awt.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaddleOcrService {

    @Value("${paddle.ocr.enabled:true}")
    private boolean paddleEnabled;

    @Value("${paddle.ocr.use-angle-cls:true}")
    private boolean useAngleCls;

    @Value("${paddle.ocr.min-confidence:35.0}")
    private double minConfidence;

    private volatile RapidOCR rapidOcr;

    // Minimum long-side dimension before upscaling
    private static final int MIN_LONG_SIDE = 1400;
    // Maximum long-side dimension to avoid OOM
    private static final int MAX_LONG_SIDE = 3200;
    // Minimum text length to consider a pass successful without retry
    private static final int MIN_TEXT_LENGTH_BEFORE_RETRY = 80;

    // French number words + Moroccan currency terms, longest first to avoid partial matches
    private static final Pattern AMOUNT_SPLIT_PATTERN = Pattern.compile(
        "(?<=[A-ZÀÂÉÈÊËÏÎÔÙÛÜÇ])(?=" +
        "MILLIARDS?|MILLIONS?|MILLE|CENTIMES?|DIRHAMS?|" +
        "SOIXANTEDIX|QUATREVINGTDIX|QUATREVINGTS?|SOIXANTE|CINQUANTE|QUARANTE|TRENTE|VINGT|" +
        "SEIZE|QUINZE|QUATORZE|TREIZE|DOUZE|ONZE|DIX|" +
        "NEUF|HUIT|SEPT|SIX|CINQ|QUATRE|TROIS|DEUX|Z[EÉ]RO|" +
        "CENTS?|CTS?|CT(?=[^A-Z]|$)|ET(?=[A-Z])|UNE?(?=[A-Z]|$)" +
        ")"
    );

    // Detects a line that is likely a concatenated amount in letters:
    // long, uppercase, no spaces, contains at least one currency/number word
    private static final Pattern CONCAT_AMOUNT_LINE = Pattern.compile(
        "(?i).*(DIRHAM|CENTIME|MILLE|MILLION|MILLIARD|VINGT|TRENTE|QUARANTE|CINQUANTE|SOIXANTE|CENT).*"
    );

    @PostConstruct
    public void warmupAsync() {
        if (!paddleEnabled) {
            return;
        }
        Thread warmupThread = new Thread(() -> {
            try {
                BufferedImage warmupImage = new BufferedImage(220, 80, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = warmupImage.createGraphics();
                try {
                    g2d.setColor(Color.WHITE);
                    g2d.fillRect(0, 0, warmupImage.getWidth(), warmupImage.getHeight());
                    g2d.setColor(Color.BLACK);
                    g2d.setFont(new Font("Arial", Font.PLAIN, 20));
                    g2d.drawString("123.45", 20, 50);
                } finally {
                    g2d.dispose();
                }
                extractText(warmupImage, "warmup", false);
                log.info("PaddleOCR warmup completed");
            } catch (Exception e) {
                log.warn("PaddleOCR warmup skipped: {}", e.getMessage());
            }
        }, "paddle-ocr-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    public boolean isEnabled() {
        return true;
    }

    public OcrResult extractText(BufferedImage image, String stage) {
        return extractText(image, stage, true);
    }

    public OcrResult extract(BufferedImage image, boolean fastMode, int attemptNumber, String stage) {
        return extractText(image, stage, !fastMode);
    }

    public OcrResult extractHighQuality(BufferedImage image, String stage) {
        return extractText(image, stage, true);
    }

    public OcrResult extractText(BufferedImage image, String stage, boolean useAngleCls) {
        if (image == null) {
            return OcrResult.failed("Image PaddleOCR nulle");
        }

        try {
            long start = System.currentTimeMillis();
            BufferedImage prepared = enhanceForOcr(image);

            // Pass 1: with requested angle cls
            String text = runOcrPass(prepared, useAngleCls, false);

            // Pass 2: flip angle cls if text is sparse
            if (text.length() < MIN_TEXT_LENGTH_BEFORE_RETRY) {
                String retry = runOcrPass(prepared, !useAngleCls, false);
                if (retry.length() > text.length()) {
                    log.debug("OCR pass 2 produced more text ({} > {}), using retry", retry.length(), text.length());
                    text = retry;
                }
            }

            // Pass 3: word-level if still sparse
            if (text.length() < MIN_TEXT_LENGTH_BEFORE_RETRY) {
                String wordLevel = runOcrPass(prepared, useAngleCls, true);
                if (wordLevel.length() > text.length()) {
                    log.debug("OCR word-level pass produced more text ({}), using it", wordLevel.length());
                    text = wordLevel;
                }
            }

            text = postProcessText(text);

            io.github.hzkitty.entity.OcrResult rapidResult = runRawOcr(prepared, useAngleCls, false);
            double confidence = extractConfidence(rapidResult);
            long duration = System.currentTimeMillis() - start;

            return OcrResult.builder()
                    .text(text)
                    .confidence(confidence)
                    .attemptNumber(1)
                    .success(!text.isBlank() && confidence >= minConfidence)
                    .processingTimeMs(duration)
                    .errorMessage(text.isBlank() ? "RapidOCR n'a extrait aucun texte utile" : null)
                    .telemetry(buildTelemetry(rapidResult, duration, stage, confidence))
                    .build();
        } catch (Exception e) {
            log.warn("Fallback RapidOCR impossible: {}", e.getMessage());
            return OcrResult.failed("RapidOCR failed: " + e.getMessage());
        }
    }

    public OcrTextWithBoxes extractTextWithBoxes(BufferedImage image, String stage, boolean useAngleCls) {
        if (image == null) {
            return OcrTextWithBoxes.failed("Image PaddleOCR nulle");
        }

        try {
            long start = System.currentTimeMillis();
            BufferedImage prepared = enhanceForOcr(image);

            ParamConfig paramConfig = new ParamConfig();
            paramConfig.setUseDet(true);
            paramConfig.setUseRec(true);
            paramConfig.setUseCls(useAngleCls);
            paramConfig.setReturnWordBox(true);
            paramConfig.setReturnWordLevel(true);

            io.github.hzkitty.entity.OcrResult rapidResult = getOrCreateEngine().run(prepared, paramConfig);
            List<OcrTextBox> boxes = extractTextBoxes(rapidResult);
            String text = rapidResult != null && rapidResult.getStrRes() != null
                    ? postProcessText(rapidResult.getStrRes().trim())
                    : postProcessText(boxes.stream().map(OcrTextBox::text).collect(Collectors.joining("\n")).trim());
            double confidence = extractConfidence(rapidResult);
            long duration = System.currentTimeMillis() - start;

            return new OcrTextWithBoxes(true, text, confidence, duration, boxes, null);
        } catch (Exception e) {
            log.warn("RapidOCR box extraction failed: {}", e.getMessage());
            return OcrTextWithBoxes.failed("RapidOCR boxes failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Image enhancement
    // -------------------------------------------------------------------------

    private BufferedImage enhanceForOcr(BufferedImage image) {
        if (image == null) {
            return null;
        }

        // Step 1: ensure RGB (no alpha, no grayscale issues)
        BufferedImage rgb;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, null);
            g.dispose();
        } else {
            rgb = image;
        }

        // Step 2: upscale if image is too small (OCR struggles below ~1200px)
        int w = rgb.getWidth();
        int h = rgb.getHeight();
        int longSide = Math.max(w, h);
        if (longSide < MIN_LONG_SIDE) {
            double scale = (double) MIN_LONG_SIDE / longSide;
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));
            BufferedImage upscaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = upscaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(rgb, 0, 0, newW, newH, null);
            g.dispose();
            rgb = upscaled;
            log.debug("OCR upscale: {}x{} -> {}x{}", w, h, newW, newH);
        }

        // Step 3: cap at max dimension to avoid OOM
        w = rgb.getWidth();
        h = rgb.getHeight();
        longSide = Math.max(w, h);
        if (longSide > MAX_LONG_SIDE) {
            double scale = (double) MAX_LONG_SIDE / longSide;
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));
            BufferedImage capped = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = capped.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(rgb, 0, 0, newW, newH, null);
            g.dispose();
            rgb = capped;
        }

        // Step 4: slight contrast enhancement to make faint text pop
        try {
            RescaleOp rescale = new RescaleOp(1.15f, -8f, null);
            rgb = rescale.filter(rgb, null);
        } catch (Exception e) {
            log.debug("Contrast enhancement skipped: {}", e.getMessage());
        }

        return rgb;
    }

    // -------------------------------------------------------------------------
    // OCR passes
    // -------------------------------------------------------------------------

    private String runOcrPass(BufferedImage image, boolean cls, boolean wordLevel) {
        try {
            io.github.hzkitty.entity.OcrResult result = runRawOcr(image, cls, wordLevel);
            if (result == null || result.getStrRes() == null) {
                return "";
            }
            return result.getStrRes().trim();
        } catch (Exception e) {
            log.debug("OCR pass failed (cls={} wordLevel={}): {}", cls, wordLevel, e.getMessage());
            return "";
        }
    }

    private io.github.hzkitty.entity.OcrResult runRawOcr(BufferedImage image, boolean cls, boolean wordLevel) throws Exception {
        ParamConfig paramConfig = new ParamConfig();
        paramConfig.setUseDet(true);
        paramConfig.setUseRec(true);
        paramConfig.setUseCls(cls);
        paramConfig.setReturnWordBox(wordLevel);
        paramConfig.setReturnWordLevel(wordLevel);
        return getOrCreateEngine().run  (image, paramConfig);
    }

    // -------------------------------------------------------------------------
    // Post-processing
    // -------------------------------------------------------------------------

    private String postProcessText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(processLine(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String processLine(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 10) {
            return line;
        }
        String upper = trimmed.toUpperCase();
        // Only process lines that look like concatenated amounts in letters:
        // uppercase, no spaces (or very few), and contains a currency/number keyword
        boolean noSpaces = !upper.contains(" ") || (double) upper.chars().filter(c -> c == ' ').count() / upper.length() < 0.04;
        if (noSpaces && CONCAT_AMOUNT_LINE.matcher(upper).matches()) {
            return normalizeAmountInLetters(upper);
        }
        return line;
    }

    /**
     * Normalise un montant en lettres collé (sans espaces) typique des factures marocaines.
     * Exemple: "DEUXMIELEDEUXCENTTRENTESEPTDIRHAMSET:OO.CTS"
     *       -> "DEUX MILLE DEUX CENT TRENTE SEPT DIRHAMS ET 00 CTS"
     */
    public String normalizeAmountInLetters(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String text = raw.toUpperCase()
                // Fix common OCR substitutions
                .replace("MIELE", "MILLE")
                .replace("MÍLLE", "MILLE")
                .replace("MlLLE", "MILLE")
                .replace("MlLE", "MILLE")
                // Fix digit/letter confusion in amounts
                .replaceAll(":0+\\.?", " 00 ")
                .replaceAll(":O+\\.?", " 00 ")
                .replace(":OO", " 00")
                .replace(":00", " 00")
                // Remove stray punctuation between letters
                .replaceAll("(?<=[A-Z])[:\\|](?=[A-Z0-9])", " ")
                // Normalize "ET:" → "ET"
                .replaceAll("ET[:\\.]", "ET ")
                // Fix "DIRHAMSET" → "DIRHAMS ET"
                .replace("DIRHAMSET", "DIRHAMS ET")
                .replace("DIRHAMET", "DIRHAM ET")
                // Fix "CTS" suffix stuck to previous word
                .replaceAll("(?<=[A-Z])(CTS|CT)$", " $1");

        // Insert spaces before each known French/Moroccan number word
        text = AMOUNT_SPLIT_PATTERN.matcher(text).replaceAll(" ");

        // Collapse multiple spaces and trim
        return text.replaceAll("\\s{2,}", " ").trim();
    }

    // -------------------------------------------------------------------------
    // Engine lifecycle
    // -------------------------------------------------------------------------

    private RapidOCR getOrCreateEngine() {
        RapidOCR local = rapidOcr;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (rapidOcr == null) {
                rapidOcr = RapidOCR.create();
                log.info("RapidOCR4j initialise");
            }
            return rapidOcr;
        }
    }

    // -------------------------------------------------------------------------
    // Confidence & telemetry
    // -------------------------------------------------------------------------

    private double extractConfidence(io.github.hzkitty.entity.OcrResult rapidResult) {
        if (rapidResult == null || rapidResult.getRecRes() == null || rapidResult.getRecRes().isEmpty()) {
            return 0.0;
        }
        double average = rapidResult.getRecRes().stream()
                .map(RecResult::getConfidence)
                .mapToDouble(Float::doubleValue)
                .average()
                .orElse(0.0);
        return Math.round(average * 100.0 * 100.0) / 100.0;
    }

    private Map<String, Object> buildTelemetry(
            io.github.hzkitty.entity.OcrResult rapidResult,
            long duration,
            String stage,
            double confidence) {
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("engine", "rapidocr4j");
        telemetry.put("stage", stage);
        telemetry.put("processingTimeMs", duration);
        telemetry.put("lineCount", rapidResult != null && rapidResult.getRecRes() != null ? rapidResult.getRecRes().size() : 0);
        telemetry.put("useAngleCls", useAngleCls);
        telemetry.put("detTimeMs", rapidResult != null ? rapidResult.getDetTime() : 0.0);
        telemetry.put("clsTimeMs", rapidResult != null ? rapidResult.getClsTime() : 0.0);
        telemetry.put("recTimeMs", rapidResult != null ? rapidResult.getRecTime() : 0.0);
        telemetry.put("confidence", confidence);
        if (rapidResult != null && rapidResult.getRecRes() != null) {
            telemetry.put("linesPreview", rapidResult.getRecRes().stream()
                    .map(RecResult::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .limit(5)
                    .collect(Collectors.toList()));
        }
        return telemetry;
    }

    // -------------------------------------------------------------------------
    // Bounding boxes
    // -------------------------------------------------------------------------

    private List<OcrTextBox> extractTextBoxes(io.github.hzkitty.entity.OcrResult rapidResult) {
        List<OcrTextBox> boxes = new ArrayList<>();
        if (rapidResult == null || rapidResult.getRecRes() == null) {
            return boxes;
        }
        for (RecResult rec : rapidResult.getRecRes()) {
            if (rec == null) {
                continue;
            }
            String text = rec.getText() == null ? "" : rec.getText().trim();
            if (text.isBlank()) {
                continue;
            }
            BoundingRect rect = readRectByReflection(rec);
            if (rect == null || rect.width <= 0 || rect.height <= 0) {
                continue;
            }
            boxes.add(new OcrTextBox(
                    text,
                    Math.max(0d, Math.min(1d, rec.getConfidence())),
                    rect.x,
                    rect.y,
                    rect.width,
                    rect.height));
        }
        return boxes;
    }

    private BoundingRect readRectByReflection(Object rec) {
        Object raw = tryInvokeFirst(rec, "getDtBoxes", "getBox", "getBoxes", "getPoints", "getBbox", "getBoundingBox");
        if (raw == null) {
            return null;
        }
        if (raw instanceof int[][] ints && ints.length > 0) {
            return fromPoints(ints);
        }
        if (raw instanceof float[][] floats && floats.length > 0) {
            return fromPoints(floats);
        }
        if (raw instanceof double[][] doubles && doubles.length > 0) {
            return fromPoints(doubles);
        }
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return fromList(list);
        }
        return null;
    }

    private Object tryInvokeFirst(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private BoundingRect fromPoints(int[][] points) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] point : points) {
            if (point == null || point.length < 2) continue;
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BoundingRect(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private BoundingRect fromPoints(float[][] points) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (float[] point : points) {
            if (point == null || point.length < 2) continue;
            int x = Math.round(point[0]);
            int y = Math.round(point[1]);
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BoundingRect(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private BoundingRect fromPoints(double[][] points) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (double[] point : points) {
            if (point == null || point.length < 2) continue;
            int x = (int) Math.round(point[0]);
            int y = (int) Math.round(point[1]);
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BoundingRect(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private BoundingRect fromList(List<?> list) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Object pointObj : list) {
            if (!(pointObj instanceof List<?> point) || point.size() < 2) continue;
            Object xObj = point.get(0);
            Object yObj = point.get(1);
            if (!(xObj instanceof Number xNum) || !(yObj instanceof Number yNum)) continue;
            int x = (int) Math.round(xNum.doubleValue());
            int y = (int) Math.round(yNum.doubleValue());
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new BoundingRect(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    private record BoundingRect(int x, int y, int width, int height) {}

    public record OcrTextBox(String text, double confidence, int x, int y, int width, int height) {}

    public record OcrTextWithBoxes(
            boolean success,
            String text,
            double confidence,
            long processingTimeMs,
            List<OcrTextBox> boxes,
            String errorMessage) {
        public static OcrTextWithBoxes failed(String errorMessage) {
            return new OcrTextWithBoxes(false, "", 0.0, 0L, List.of(), errorMessage);
        }
    }
}
