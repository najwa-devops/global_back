package com.invoice_reader.invoice_reader.ocr.service;

import com.invoice_reader.invoice_reader.ocr.dto.OcrResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DocumentQualityScoringService {

    private final ImagePreprocessingService imagePreprocessingService;

    public DocumentQualityScoringService(ImagePreprocessingService imagePreprocessingService) {
        this.imagePreprocessingService = imagePreprocessingService;
    }

    public DocumentQualityAssessment assess(Path path, OcrResult ocrResult, boolean scanned) {
        List<String> flags = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        int score = 100;

        String text = ocrResult != null && ocrResult.getText() != null ? ocrResult.getText() : "";
        double confidence = ocrResult != null ? ocrResult.getConfidence() : 0.0;
        Map<String, Object> telemetry = ocrResult != null && ocrResult.getTelemetry() != null
                ? ocrResult.getTelemetry()
                : Map.of();

        metrics.put("ocrConfidence", confidence);
        metrics.put("textLength", text.length());
        metrics.put("scanned", scanned);
        metrics.put("fallbackTriggered", asBoolean(telemetry.get("fallbackTriggered")));

        if (scanned) {
            flags.add("SCAN_DOCUMENT");
            score -= 8;
        }

        if (confidence < 55.0) {
            flags.add("OCR_FAIBLE");
            score -= 18;
        } else if (confidence < 72.0) {
            flags.add("OCR_MOYEN");
            score -= 10;
        }

        if (text.length() < 120) {
            flags.add("TEXTE_TRES_FAIBLE");
            score -= 18;
        } else if (text.length() < 260) {
            flags.add("TEXTE_FAIBLE");
            score -= 10;
        }

        if (asBoolean(telemetry.get("fallbackTriggered"))) {
            flags.add("FALLBACK_PLEINE_PAGE");
            score -= 7;
        }

        try {
            BufferedImage image = loadRepresentativeImage(path);
            if (image != null) {
                ImageMetrics imageMetrics = analyzeImage(image);
                metrics.putAll(imageMetrics.toMap());

                if (imageMetrics.lowContrast()) {
                    flags.add("FAIBLE_CONTRASTE");
                    score -= 14;
                }
                if (imageMetrics.noisyScan()) {
                    flags.add("SCAN_BRUITE");
                    score -= 12;
                }
                if (imageMetrics.skewed()) {
                    flags.add("IMAGE_INCLINEE");
                    score -= 10;
                }
                if (imageMetrics.smallText()) {
                    flags.add("TEXTE_PETIT");
                    score -= 12;
                }
                if (imageMetrics.denseTable()) {
                    flags.add("TABLEAU_DENSE");
                    score -= 9;
                }
            }
        } catch (Exception e) {
            log.debug("Analyse qualité image ignorée: {}", e.getMessage());
            metrics.put("qualityImageAnalysisSkipped", true);
        }

        score = Math.max(0, Math.min(100, score));
        DocumentDifficultyClass difficultyClass = score >= 75
                ? DocumentDifficultyClass.NORMAL
                : score >= 45
                ? DocumentDifficultyClass.DIFFICILE
                : DocumentDifficultyClass.TRES_DIFFICILE;

        metrics.put("qualityScore", score);
        metrics.put("difficultyClass", difficultyClass.name());
        return new DocumentQualityAssessment(score, difficultyClass, flags, metrics);
    }

    private BufferedImage loadRepresentativeImage(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(path.toFile())) {
                if (document.getNumberOfPages() == 0) {
                    return null;
                }
                return new PDFRenderer(document).renderImageWithDPI(0, 180);
            }
        }
        return ImageIO.read(path.toFile());
    }

    private ImageMetrics analyzeImage(BufferedImage image) throws IOException {
        Mat source = imagePreprocessingService.bufferedImageToMat(image);
        Mat gray = new Mat();
        if (source.channels() > 1) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = source.clone();
        }

        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(gray, mean, stddev);
        double contrastStd = stddev.toArray().length > 0 ? stddev.toArray()[0] : 0.0;

        Mat blurred = new Mat();
        Imgproc.medianBlur(gray, blurred, 3);
        Mat diff = new Mat();
        Core.absdiff(gray, blurred, diff);
        Scalar noiseMean = Core.mean(diff);
        double noiseLevel = noiseMean.val[0];

        double skewAngle = detectSkewAngle(gray);
        double avgComponentHeight = estimateAverageTextHeight(gray);
        double tableDensity = estimateTableDensity(gray);

        boolean lowContrast = contrastStd < 42.0;
        boolean noisyScan = noiseLevel > 14.0;
        boolean skewed = Math.abs(skewAngle) >= 1.2;
        boolean smallText = avgComponentHeight > 0.0 && avgComponentHeight < 18.0;
        boolean denseTable = tableDensity > 0.12;

        return new ImageMetrics(
                contrastStd,
                noiseLevel,
                skewAngle,
                avgComponentHeight,
                tableDensity,
                lowContrast,
                noisyScan,
                skewed,
                smallText,
                denseTable
        );
    }

    private double detectSkewAngle(Mat gray) {
        try {
            Mat threshold = new Mat();
            Imgproc.threshold(gray, threshold, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);
            Mat points = new Mat();
            Core.findNonZero(threshold, points);
            if (points.rows() == 0) {
                return 0.0;
            }
            MatOfPoint2f points2f = new MatOfPoint2f();
            points.convertTo(points2f, CvType.CV_32F);
            RotatedRect box = Imgproc.minAreaRect(points2f);
            double angle = box.angle;
            if (angle < -45.0) {
                angle += 90.0;
            }
            return Math.round(angle * 100.0) / 100.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double estimateAverageTextHeight(Mat gray) {
        try {
            Mat binary = new Mat();
            Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(binary, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            double totalHeight = 0.0;
            int count = 0;
            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = rect.width * rect.height;
                if (area < 20 || area > 4000) {
                    continue;
                }
                if (rect.height < 5 || rect.height > 80) {
                    continue;
                }
                totalHeight += rect.height;
                count++;
            }
            return count == 0 ? 0.0 : totalHeight / count;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double estimateTableDensity(Mat gray) {
        try {
            Mat binary = new Mat();
            Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU);

            Mat horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(30, 1));
            Mat verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, 30));
            Mat horizontal = new Mat();
            Mat vertical = new Mat();
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel);
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel);
            Mat tableMask = new Mat();
            Core.add(horizontal, vertical, tableMask);
            double nonZero = Core.countNonZero(tableMask);
            double total = (double) tableMask.rows() * tableMask.cols();
            return total <= 0 ? 0.0 : nonZero / total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private record ImageMetrics(
            double contrastStd,
            double noiseLevel,
            double skewAngle,
            double averageTextHeight,
            double tableDensity,
            boolean lowContrast,
            boolean noisyScan,
            boolean skewed,
            boolean smallText,
            boolean denseTable
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contrastStd", contrastStd);
            payload.put("noiseLevel", noiseLevel);
            payload.put("skewAngle", skewAngle);
            payload.put("averageTextHeight", averageTextHeight);
            payload.put("tableDensity", tableDensity);
            return payload;
        }
    }
}
