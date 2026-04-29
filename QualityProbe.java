import com.invoice_reader.invoice_reader.servises.ocr.*;
import com.invoice_reader.invoice_reader.config.OpenCvConfig;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

public class QualityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: QualityProbe <folder>...");
            System.exit(1);
        }

        new OpenCvConfig().loadOpenCvNativeLibrary();

        ImagePreprocessingService preprocessing = new ImagePreprocessingService();
        TesseractConfigService tesseract = new TesseractConfigService();
        set(tesseract, "tessDataPath", "C:\\Program Files\\Tesseract-OCR\\tessdata");
        set(tesseract, "language", "fra+eng");
        set(tesseract, "fastImageLanguage", "fra");

        PaddleOcrService paddle = new PaddleOcrService("ONNX_PPOCR_V4", 2);
        OcrValidationService validation = new OcrValidationService();
        OcrPostProcessor post = new OcrPostProcessor();
        OcrFallbackStrategy fallback = new OcrFallbackStrategy(tesseract, preprocessing);
        AdvancedOcrService advanced = new AdvancedOcrService(preprocessing, tesseract, paddle, validation, fallback, post);
        set(advanced, "ocrEngine", "paddle");

        DocumentQualityScoringService scoring = new DocumentQualityScoringService(preprocessing);
        TextCleaningService cleaning = new TextCleaningService();
        CommonInvoiceOcrService common = new CommonInvoiceOcrService(advanced, cleaning, scoring);

        for (String rootArg : args) {
            Path root = Paths.get(rootArg);
            System.out.println("== FOLDER == " + root);
            List<Path> files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
            int ge75 = 0;
            int total = 0;
            for (Path file : files) {
                total++;
                try {
                    CommonInvoiceOcrData data = common.analyze(file);
                    int quality = data.qualityAssessment() != null ? data.qualityAssessment().qualityScore() : -1;
                    String difficulty = data.qualityAssessment() != null && data.qualityAssessment().difficultyClass() != null
                            ? data.qualityAssessment().difficultyClass().name() : "UNKNOWN";
                    List<String> flags = data.qualityAssessment() != null ? data.qualityAssessment().qualityFlags() : List.of();
                    double conf = data.ocrResult() != null ? data.ocrResult().getConfidence() : 0.0;
                    int len = data.rawText() != null ? data.rawText().length() : 0;
                    Object engineUsed = data.ocrResult() != null && data.ocrResult().getTelemetry() != null
                            ? data.ocrResult().getTelemetry().get("ocrEngineUsed") : null;
                    if (quality >= 75) ge75++;
                    System.out.printf(Locale.ROOT,
                            "%s | score=%d | diff=%s | conf=%.1f | len=%d | scanned=%s | engine=%s | flags=%s%n",
                            file.getFileName(), quality, difficulty, conf, len, data.scanned(), engineUsed, flags);
                } catch (Throwable t) {
                    System.out.println(file.getFileName() + " | ERROR | " + t.getClass().getSimpleName() + " | " + t.getMessage());
                }
            }
            System.out.println("SUMMARY | >=75=" + ge75 + "/" + total + " | below75=" + (total - ge75));
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
