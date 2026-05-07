package com.invoice_reader.invoice_reader.sales.service.ocr;

import com.invoice_reader.invoice_reader.dto.ocr.OcrResult;
import com.invoice_reader.invoice_reader.servises.ocr.ImagePreprocessingService;
import com.invoice_reader.invoice_reader.servises.ocr.OcrFallbackStrategy;
import com.invoice_reader.invoice_reader.servises.ocr.OcrPostProcessor;
import com.invoice_reader.invoice_reader.servises.ocr.OcrValidationService;
import com.invoice_reader.invoice_reader.servises.ocr.TesseractConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.opencv.core.Mat;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SalesAdvancedOcrService {

    private final ImagePreprocessingService preprocessingService;
    private final TesseractConfigService tesseractConfigService;
    private final OcrValidationService ocrValidationService;
    private final OcrFallbackStrategy ocrFallbackStrategy;
    private final OcrPostProcessor ocrPostProcessor;

    private static final int MIN_TEXT_LENGTH = 100;
    private static final double MIN_CONFIDENCE = 60.0;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    // Zone segmentation configuration
    private static final double HEADER_HEIGHT_RATIO = 0.30; // 30% top
    private static final double BODY_HEIGHT_RATIO = 0.40; // 40% middle
    private static final double FOOTER_HEIGHT_RATIO = 0.30; // 30% bottom
    private static final String[] FOOTER_KEYWORDS = { "ICE", "I.F", " IF", "RC", "R.C", "CNSS", "PATENTE", "IF",
            "IDENTIFIANT", "FISCAL" };
    private static final int PDF_RENDER_DPI = 300;
    private static final long MAX_IMAGE_PIXELS = 6_000_000; // ~2500x2400
    private static final int MAX_IMAGE_DIMENSION = 2800;

    /**
     * Zone types for segmented OCR
     */
    private enum ZoneType {
        HEADER, // 0-30%: Supplier, Invoice #, Date
        BODY, // 30-70%: Line items, tables
        FOOTER // 70-100%: ICE, IF, RC, amounts
    }

    public OcrResult extractTextAdvanced(Path imagePath) {
        log.info("=== DÉBUT EXTRACTION OCR AVANCÉE ===");
        log.info("Fichier: {}", imagePath.getFileName());

        long startTime = System.currentTimeMillis();

        try {
            // ARCHITECTURE PRO: Vérifier PDF DIGITAL avant OCR
            String filename = imagePath.getFileName().toString().toLowerCase();
            if (filename.endsWith(".pdf")) {
                log.info("PDF détecté - Tentative extraction texte native...");

                try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument
                        .load(imagePath.toFile())) {

                    org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    String nativeText = stripper.getText(document);

                    // Si texte natif suffisant (>100 caractères), n'enrichir par OCR
                    // que si des indices footer critiques sont absents.
                    if (nativeText != null && nativeText.trim().length() > 100) {
                        boolean enrichFooter = shouldEnrichPdfFooter(nativeText);
                        String mergedText = enrichFooter ? mergeNativePdfWithOcr(document, nativeText) : nativeText;
                        long processingTime = System.currentTimeMillis() - startTime;

                        log.info("PDF DIGITAL: {} caractères extraits SANS OCR", nativeText.length());
                        if (enrichFooter && !mergedText.equals(nativeText)) {
                            log.info("Enrichissement OCR PDF: {} -> {} caractères",
                                    nativeText.length(), mergedText.length());
                        }
                        log.info(" Temps: {}ms (vs ~5000ms avec OCR)", processingTime);
                        log.info("Qualité: 100% (texte natif, pas d'erreur OCR)");

                        return OcrResult.builder()
                                .text(mergedText)
                                .confidence(mergedText.equals(nativeText) ? 100.0 : 96.0)
                                .processingTimeMs(processingTime)
                                .success(true)
                                .build();
                    } else {
                        log.info("PDF scanné ou texte insuffisant ({} chars) → OCR nécessaire",
                                nativeText != null ? nativeText.trim().length() : 0);
                    }
                } catch (Exception e) {
                    log.warn("Échec extraction native PDF: {} → Fallback OCR", e.getMessage());
                }
            }

            // ÉTAPE 1: Charger l'image (PDF scanné ou image directe)
            BufferedImage originalImage = loadImage(imagePath);
            originalImage = downscaleIfNeeded(originalImage);
            log.info("Image chargée: {}x{}", originalImage.getWidth(), originalImage.getHeight());

            // ✅ ÉTAPE 2: ZONE-BASED OCR (NOUVEAU - Phase 2)
            // Segmentation Header/Body/Footer pour OCR ciblé
            log.info("🎯 Activation OCR par zones (Header/Body/Footer)");
            OcrResult zonedResult = performZonedOcr(originalImage);

            if (zonedResult != null && zonedResult.isSuccess()) {
                zonedResult.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                log.info("✅ OCR par zones réussi: {} chars, {}% confiance, {}ms",
                        zonedResult.getText().length(),
                        (int) zonedResult.getConfidence(),
                        zonedResult.getProcessingTimeMs());
                return zonedResult;
            }

            // FALLBACK: Si OCR par zones échoue, utiliser approche full-page classique
            log.warn("⚠️ OCR par zones échoué, fallback sur full-page OCR");

            // ÉTAPE 3: Pipeline de pré-traitement (FALLBACK)
            List<BufferedImage> preprocessedVariants = preprocessingService.preprocessImage(originalImage);
            log.info("Variantes pré-traitées générées: {}", preprocessedVariants.size());

            // ÉTAPE 4: Tentatives OCR multiples (FALLBACK)
            List<OcrResult> results = new ArrayList<>();

            for (int i = 0; i < preprocessedVariants.size(); i++) {
                BufferedImage variant = preprocessedVariants.get(i);
                log.info("Tentative OCR #{} ...", i + 1);

                OcrResult result = performOcrWithConfig(variant, i);
                results.add(result);

                // Validation immédiate
                if (ocrValidationService.isHighQuality(result)) {
                    log.info("Résultat haute qualité trouvé à la tentative #{}", i + 1);
                    result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // ÉTAPE 5: Fallback strategy si aucun résultat de qualité
            log.warn("Aucun résultat haute qualité - Activation fallback");
            OcrResult fallbackResult = ocrFallbackStrategy.executeFallback(originalImage, results);

            if (fallbackResult != null && ocrValidationService.isAcceptable(fallbackResult)) {
                log.info("Fallback réussi");
                fallbackResult.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                return fallbackResult;
            }

            // ÉTAPE 6: Sélection du meilleur résultat disponible
            OcrResult bestResult = selectBestResult(results);

            bestResult.setProcessingTimeMs(System.currentTimeMillis() - startTime);

            log.warn("Qualité sous-optimale: {} caractères, confiance {}%",
                    bestResult.getText().length(),
                    bestResult.getConfidence());

            return bestResult;

        } catch (Exception e) {
            log.error("Erreur critique OCR: {}", e.getMessage(), e);
            return OcrResult.failed(e.getMessage());
        }
    }

    /**
     * Charge une image depuis le filesystem
     * Supporte les images (PNG, JPG) et les PDFs (convertis en image)
     */
    private BufferedImage loadImage(Path imagePath) throws IOException {
        File imageFile = imagePath.toFile();
        if (!imageFile.exists()) {
            throw new IOException("Fichier introuvable: " + imagePath);
        }

        String filename = imagePath.getFileName().toString().toLowerCase();

        // CAS 1: PDF → Essayer extraction texte native AVANT OCR
        if (filename.endsWith(".pdf")) {
            log.info("Détection PDF, tentative extraction texte native...");
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(imageFile)) {

                // ÉTAPE 1: Essayer extraction texte native (PDFs digitaux)
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                String nativeText = stripper.getText(document);

                // Si texte natif suffisant (>100 caractères), pas besoin d'OCR !
                if (nativeText != null && nativeText.trim().length() > 100) {
                    log.info("PDF DIGITAL détecté: {} caractères extraits SANS OCR", nativeText.length());
                    log.info(" Gain de temps: OCR évité (PDF contient du texte natif)");

                    // Créer une image blanche avec le texte (pour compatibilité avec le pipeline)
                    // Le texte sera retourné directement dans extractTextAdvanced
                    BufferedImage dummyImage = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);

                    // Stocker le texte natif dans un attribut temporaire
                    // Note: On pourrait améliorer en créant un champ de classe
                    log.debug("Texte natif extrait (preview): {}",
                            nativeText.substring(0, Math.min(200, nativeText.length())));

                    // Pour l'instant, on continue avec OCR mais on log qu'on pourrait l'éviter
                    // TODO: Refactorer pour retourner directement le texte sans passer par OCR
                }

                // ÉTAPE 2: Si texte natif insuffisant → Conversion en image pour OCR
                log.info("PDF scanné ou texte insuffisant, conversion en image pour OCR...");
                org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(
                        document);
                // 300 DPI = meilleur équilibre qualité/performance
                BufferedImage image = renderer.renderImageWithDPI(0, PDF_RENDER_DPI);
                log.info("PDF converti: {}x{} pixels @ {} DPI",
                        image.getWidth(), image.getHeight(), PDF_RENDER_DPI);
                return image;

            } catch (Exception e) {
                throw new IOException("Erreur traitement PDF: " + e.getMessage(), e);
            }
        }

        // CAS 2: Image standard
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("Format d'image non supporté: " + imagePath);
        }

        return image;
    }

    private BufferedImage downscaleIfNeeded(BufferedImage image) {
        if (image == null) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * (long) height;

        if (pixels <= MAX_IMAGE_PIXELS && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return image;
        }

        double scaleByPixels = Math.sqrt((double) MAX_IMAGE_PIXELS / (double) pixels);
        double scaleByDimension = Math.min(
                (double) MAX_IMAGE_DIMENSION / (double) width,
                (double) MAX_IMAGE_DIMENSION / (double) height
        );
        double scale = Math.min(scaleByPixels, scaleByDimension);

        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight,
                image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();

        log.info("Downscale image: {}x{} -> {}x{} (pixels: {} -> {})",
                width, height, newWidth, newHeight, pixels, (long) newWidth * newHeight);

        return scaled;
    }

    private boolean isLargeImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * (long) height;
        return pixels > MAX_IMAGE_PIXELS || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION;
    }

    /**
     * Exécution OCR avec configuration spécifique
     */
    private OcrResult performOcrWithConfig(BufferedImage image, int attemptNumber) {
        try {
            // Configuration Tesseract selon tentative
            Tesseract tesseract = tesseractConfigService.createConfiguredInstance(attemptNumber);

            // Exécution OCR
            String text = tesseract.doOCR(image);

            // Calcul confiance (Tesseract 4+ avec LSTM)
            // Note: Pour confiance réelle, utiliser tesseract.getWords() mais plus lourd
            double confidence = estimateConfidence(text);

            return OcrResult.builder()
                    .text(text != null ? text : "")
                    .confidence(confidence)
                    .attemptNumber(attemptNumber + 1)
                    .imageWidth(image.getWidth())
                    .imageHeight(image.getHeight())
                    .success(true)
                    .build();

        } catch (TesseractException e) {
            log.error("Erreur Tesseract tentative #{}: {}", attemptNumber + 1, e.getMessage());
            return OcrResult.builder()
                    .text("")
                    .confidence(0.0)
                    .attemptNumber(attemptNumber + 1)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Estimation confiance basée sur heuristiques
     *
     * Critères:
     * - Longueur texte
     * - Ratio caractères alphanumériques
     * - Présence mots-clés facture (FACTURE, TOTAL, etc.)
     */
    private double estimateConfidence(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        double score = 0.0;

        // Critère 1: Longueur (max 40 points)
        int length = text.length();
        if (length > 500)
            score += 40;
        else if (length > 200)
            score += 30;
        else if (length > 100)
            score += 20;
        else
            score += 10;

        // Critère 2: Ratio alphanumérique (max 30 points)
        long alphanumCount = text.chars().filter(Character::isLetterOrDigit).count();
        double alphanumRatio = (double) alphanumCount / text.length();
        score += alphanumRatio * 30;

        // Critère 3: Mots-clés facture (max 30 points)
        String upperText = text.toUpperCase();
        int keywordCount = 0;
        String[] keywords = { "FACTURE", "INVOICE", "TOTAL", "TVA", "DATE", "MONTANT", "HT", "TTC" };
        for (String keyword : keywords) {
            if (upperText.contains(keyword))
                keywordCount++;
        }
        score += Math.min(keywordCount * 5, 30);

        return Math.min(score, 100.0);
    }

    /**
     * Sélectionne le meilleur résultat parmi les tentatives
     */
    private OcrResult selectBestResult(List<OcrResult> results) {
        return results.stream()
                .filter(r -> r.getText() != null && !r.getText().isBlank())
                .max(Comparator.comparingDouble(r -> r.getConfidence() * 0.7 + (r.getText().length() / 10.0) * 0.3))
                .orElse(OcrResult.failed("Aucun texte extrait"));
    }

    /**
     * PERFORM ZONED OCR: Main method for zone-based extraction
     * Segments image into Header/Body/Footer and performs targeted OCR
     */
    private OcrResult performZonedOcr(BufferedImage originalImage) {
        try {
            log.info("=== DÉBUT OCR PAR ZONES ===");
            boolean fastMode = isLargeImage(originalImage);
            if (fastMode) {
                log.info("Fast mode activé (image large) : variantes OCR réduites");
            }

            // Extraire les 3 zones
            BufferedImage headerZone = extractZone(originalImage, ZoneType.HEADER);
            BufferedImage bodyZone = extractZone(originalImage, ZoneType.BODY);
            BufferedImage footerZone = extractZone(originalImage, ZoneType.FOOTER);

            log.info("Zones extraites: Header={}px, Body={}px, Footer={}px",
                    headerZone.getHeight(), bodyZone.getHeight(), footerZone.getHeight());

            // OCR séquentiel sur chaque zone
            OcrResult headerResult = ocrHeader(headerZone, fastMode);
            OcrResult bodyResult;
            if (fastMode) {
                // Mode rapide: on saute l'OCR du body pour gagner du temps
                bodyResult = OcrResult.builder()
                        .text("")
                        .confidence(0.0)
                        .attemptNumber(0)
                        .imageWidth(bodyZone.getWidth())
                        .imageHeight(bodyZone.getHeight())
                        .success(true)
                        .processingTimeMs(0L)
                        .build();
            } else {
                bodyResult = ocrBody(bodyZone, fastMode);
            }
            OcrResult footerResult = ocrFooter(footerZone, fastMode);

            // Vérifier succès de toutes les zones
            if (!headerResult.isSuccess() || !bodyResult.isSuccess() || !footerResult.isSuccess()) {
                log.warn("Au moins une zone a échoué - fallback nécessaire");
                return null;
            }

            // ✅ POST-TRAITEMENT: Nettoyer et corriger le texte OCR
            log.info("Application post-traitement OCR...");
            headerResult.setText(ocrPostProcessor.postProcess(headerResult.getText()));
            bodyResult.setText(ocrPostProcessor.postProcess(bodyResult.getText()));
            footerResult.setText(ocrPostProcessor.postProcess(footerResult.getText()));

            // Fusionner les résultats
            OcrResult merged = mergeZoneResults(headerResult, bodyResult, footerResult);

            return merged;

        } catch (Exception e) {
            log.error("Erreur OCR par zones: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * ZONE SEGMENTATION: Extrait une zone spécifique de l'image
     * 
     * @param image Image source
     * @param zone  Type de zone à extraire
     * @return BufferedImage de la zone extraite
     */
    private BufferedImage extractZone(BufferedImage image, ZoneType zone) {
        int height = image.getHeight();
        int width = image.getWidth();
        int startY, zoneHeight;

        switch (zone) {
            case HEADER:
                startY = 0;
                zoneHeight = (int) (height * HEADER_HEIGHT_RATIO);
                log.debug("Extraction HEADER: 0-{}% ({}px)", (int) (HEADER_HEIGHT_RATIO * 100), zoneHeight);
                break;

            case BODY:
                startY = (int) (height * HEADER_HEIGHT_RATIO);
                zoneHeight = (int) (height * BODY_HEIGHT_RATIO);
                log.debug("Extraction BODY: {}-{}% ({}px)",
                        (int) (HEADER_HEIGHT_RATIO * 100),
                        (int) ((HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO) * 100),
                        zoneHeight);
                break;

            case FOOTER:
                startY = (int) (height * (HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO));
                zoneHeight = (int) (height * FOOTER_HEIGHT_RATIO);
                log.debug("Extraction FOOTER: {}-100% ({}px)",
                        (int) ((HEADER_HEIGHT_RATIO + BODY_HEIGHT_RATIO) * 100),
                        zoneHeight);
                break;

            default:
                throw new IllegalArgumentException("Zone type non supporté: " + zone);
        }

        // Sécurité: vérifier les limites
        if (startY + zoneHeight > height) {
            zoneHeight = height - startY;
            log.warn("Ajustement hauteur zone {} pour éviter débordement", zone);
        }

        return image.getSubimage(0, startY, width, zoneHeight);
    }

    /**
     * ZONE-SPECIFIC OCR: Header (Supplier, Invoice #, Date)
     * Pipelines: LAB (priority), Standard
     */
    private OcrResult ocrHeader(BufferedImage headerZone, boolean fastMode) {
        log.info("=== OCR HEADER (Supplier, Invoice #, Date) ===");
        long startTime = System.currentTimeMillis();

        try {
            // Générer variantes ciblées pour header
            List<BufferedImage> variants = new ArrayList<>();

            // Variante 0: Original
            variants.add(headerZone);

            // Variante 1: LAB (priorité pour logos/couleurs)
            Mat headerMat = preprocessingService.bufferedImageToMat(headerZone);
            Mat labProcessed = preprocessingService.labColorSpacePipeline(headerMat);
            variants.add(preprocessingService.matToBufferedImage(labProcessed));

            if (!fastMode) {
                // Variante 2: Standard
                Mat standardProcessed = preprocessingService.standardPipeline(headerMat.clone());
                variants.add(preprocessingService.matToBufferedImage(standardProcessed));

                // Variante 3: Scans granuleux / photocopies
                Mat despeckledProcessed = preprocessingService.despeckleInvoicePipeline(headerMat.clone());
                variants.add(preprocessingService.matToBufferedImage(despeckledProcessed));
            }

            // OCR sur les variantes
            List<OcrResult> results = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                OcrResult result = performOcrWithConfig(variants.get(i), i);
                results.add(result);

                if (ocrValidationService.isHighQualityHeader(result)) {
                    log.info("✓ Header OCR haute qualité (variante #{})", i);
                    result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // Sélectionner meilleur résultat
            OcrResult best = selectBestResult(results);
            best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            log.info("Header OCR terminé: {} chars, {}% confiance",
                    best.getText().length(), (int) (best.getConfidence()));
            return best;

        } catch (Exception e) {
            log.error("Erreur OCR header: {}", e.getMessage());
            return OcrResult.failed("Header OCR failed: " + e.getMessage());
        }
    }

    /**
     * ZONE-SPECIFIC OCR: Body (Line items, tables)
     * Pipelines: Hybrid LAB+Morphology (priority), LAB, Standard
     */
    private OcrResult ocrBody(BufferedImage bodyZone, boolean fastMode) {
        log.info("=== OCR BODY (Line items, tables) ===");
        long startTime = System.currentTimeMillis();

        try {
            // Générer variantes ciblées pour body
            List<BufferedImage> variants = new ArrayList<>();

            // Variante 0: Original
            variants.add(bodyZone);

            Mat bodyMat = preprocessingService.bufferedImageToMat(bodyZone);

            if (!fastMode) {
                // Variante 1: Hybrid LAB+Morphology (priorité pour tableaux)
                Mat hybridProcessed = preprocessingService.hybridLabMorphologyPipeline(bodyMat.clone());
                variants.add(preprocessingService.matToBufferedImage(hybridProcessed));

                // Variante 2: LAB
                Mat labProcessed = preprocessingService.labColorSpacePipeline(bodyMat.clone());
                variants.add(preprocessingService.matToBufferedImage(labProcessed));

                // Variante 3: Standard
                Mat standardProcessed = preprocessingService.standardPipeline(bodyMat.clone());
                variants.add(preprocessingService.matToBufferedImage(standardProcessed));

                // Variante 4: Débruitage scans granuleux
                Mat despeckledProcessed = preprocessingService.despeckleInvoicePipeline(bodyMat.clone());
                variants.add(preprocessingService.matToBufferedImage(despeckledProcessed));
            } else {
                // Mode rapide: seulement LAB
                Mat labProcessed = preprocessingService.labColorSpacePipeline(bodyMat.clone());
                variants.add(preprocessingService.matToBufferedImage(labProcessed));
            }

            // OCR sur les variantes
            List<OcrResult> results = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                OcrResult result = performOcrWithConfig(variants.get(i), i);
                results.add(result);

                if (ocrValidationService.isHighQuality(result)) {
                    log.info("✓ Body OCR haute qualité (variante #{})", i);
                    result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // Sélectionner meilleur résultat
            OcrResult best = selectBestResult(results);
            best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            log.info("Body OCR terminé: {} chars, {}% confiance",
                    best.getText().length(), (int) (best.getConfidence()));
            return best;

        } catch (Exception e) {
            log.error("Erreur OCR body: {}", e.getMessage());
            return OcrResult.failed("Body OCR failed: " + e.getMessage());
        }
    }

    /**
     * ZONE-SPECIFIC OCR: Footer (ICE, IF, RC, amounts)
     * Pipelines: LAB (priority), Standard, Heavy Denoising
     */
    private OcrResult ocrFooter(BufferedImage footerZone, boolean fastMode) {
        log.info("=== OCR FOOTER (ICE, IF, RC, Amounts) ===");
        long startTime = System.currentTimeMillis();

        try {
            // Générer variantes ciblées pour footer
            List<BufferedImage> variants = new ArrayList<>();

            // Variante 0: Original
            variants.add(footerZone);

            Mat footerMat = preprocessingService.bufferedImageToMat(footerZone);

            // Variante 1: LAB (priorité pour fonds colorés)
            Mat labProcessed = preprocessingService.labColorSpacePipeline(footerMat.clone());
            variants.add(preprocessingService.matToBufferedImage(labProcessed));

            if (!fastMode) {
                // Variante 2: Standard
                Mat standardProcessed = preprocessingService.standardPipeline(footerMat.clone());
                variants.add(preprocessingService.matToBufferedImage(standardProcessed));
            }

            if (!fastMode) {
                // Variante 3: Heavy Denoising (pour scans de mauvaise qualité)
                Mat denoisedProcessed = preprocessingService.heavyDenoisingPipeline(footerMat.clone());
                variants.add(preprocessingService.matToBufferedImage(denoisedProcessed));

                // Variante 4: Débruitage scans granuleux / tickets photocopiés
                Mat despeckledProcessed = preprocessingService.despeckleInvoicePipeline(footerMat.clone());
                variants.add(preprocessingService.matToBufferedImage(despeckledProcessed));
            }

            // OCR sur les variantes
            List<OcrResult> results = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                OcrResult result = performOcrWithConfig(variants.get(i), i);
                results.add(result);

                if (ocrValidationService.isHighQuality(result)) {
                    log.info("✓ Footer OCR haute qualité (variante #{})", i);
                    result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // Sélectionner meilleur résultat
            OcrResult best = selectBestResult(results);
            best.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            log.info("Footer OCR terminé: {} chars, {}% confiance",
                    best.getText().length(), (int) (best.getConfidence()));
            return best;

        } catch (Exception e) {
            log.error("Erreur OCR footer: {}", e.getMessage());
            return OcrResult.failed("Footer OCR failed: " + e.getMessage());
        }
    }

    /**
     * MERGE ZONE RESULTS: Combine header, body, footer into single OcrResult
     */
    private OcrResult mergeZoneResults(OcrResult header, OcrResult body, OcrResult footer) {
        log.info("=== FUSION RÉSULTATS ZONES ===");

        // Combiner les textes avec marqueurs de zone
        StringBuilder combinedText = new StringBuilder();
        combinedText.append("[HEADER]\n");
        combinedText.append(header.getText());
        combinedText.append("\n\n[BODY]\n");
        combinedText.append(body.getText());
        combinedText.append("\n\n[FOOTER]\n");
        combinedText.append(footer.getText());

        // Calculer confiance moyenne pondérée
        // Footer = 40% (plus important pour ICE/IF/RC)
        // Body = 35% (tableaux)
        // Header = 25% (supplier/invoice#)
        double weightedConfidence = (header.getConfidence() * 0.25) +
                (body.getConfidence() * 0.35) +
                (footer.getConfidence() * 0.40);

        // Temps total = somme des temps de zone
        long totalTime = header.getProcessingTimeMs() +
                body.getProcessingTimeMs() +
                footer.getProcessingTimeMs();

        log.info("Zones fusionnées: {} chars total, {}% confiance, {}ms",
                combinedText.length(), (int) weightedConfidence, totalTime);
        log.info("  - Header: {} chars, {}%", header.getText().length(), (int) header.getConfidence());
        log.info("  - Body: {} chars, {}%", body.getText().length(), (int) body.getConfidence());
        log.info("  - Footer: {} chars, {}%", footer.getText().length(), (int) footer.getConfidence());

        return OcrResult.builder()
                .text(combinedText.toString())
                .confidence(weightedConfidence)
                .processingTimeMs(totalTime)
                .success(true)
                .build();
    }

    /**
     * LEGACY: Extrait la zone footer (15% inférieur de l'image)
     * 
     * @deprecated Use extractZone(image, ZoneType.FOOTER) instead
     */
    /**
     * Enrichit le texte natif d'un PDF avec OCR sur premiÃ¨re/derniÃ¨re page.
     * Cela rÃ©cupÃ¨re les footers souvent absents du layer texte.
     */
    @Deprecated
    private boolean shouldEnrichPdfFooter(String nativeText) {
        if (nativeText == null || nativeText.isBlank()) {
            return false;
        }
        String upper = (" " + nativeText.toUpperCase() + " ");
        for (String keyword : FOOTER_KEYWORDS) {
            if (upper.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Enrichit le texte natif d'un PDF avec OCR cible sur le footer de la derniere page.
     * On ajoute uniquement des lignes contenant des mots-cles footer.
     */
    private String mergeNativePdfWithOcr(org.apache.pdfbox.pdmodel.PDDocument document, String nativeText) {
        if (nativeText == null) {
            nativeText = "";
        }

        try {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                return nativeText;
            }

            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            Set<String> mergedLines = new LinkedHashSet<>();
            appendNonBlankLines(mergedLines, nativeText);

            int lastPage = pageCount - 1;
            BufferedImage pageImage = renderer.renderImageWithDPI(lastPage, 220);
            int footerStartY = (int) (pageImage.getHeight() * 0.55);
            int footerHeight = Math.max(1, pageImage.getHeight() - footerStartY);
            BufferedImage footerImage = pageImage.getSubimage(0, footerStartY, pageImage.getWidth(), footerHeight);

            String footerText = extractFooterTextFastVariants(footerImage);
            appendFooterKeywordLines(mergedLines, footerText);

            return String.join("\n", mergedLines).trim();
        } catch (Exception e) {
            log.warn("Enrichissement OCR PDF ignore (erreur): {}", e.getMessage());
            return nativeText;
        }
    }

    private void appendNonBlankLines(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String cleaned = line.trim();
            if (!cleaned.isBlank()) {
                target.add(cleaned);
            }
        }
    }

    private void appendFooterKeywordLines(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String cleaned = line.trim();
            if (cleaned.isBlank()) {
                continue;
            }

            String upper = (" " + cleaned.toUpperCase() + " ");
            boolean hasKeyword = false;
            for (String keyword : FOOTER_KEYWORDS) {
                if (upper.contains(keyword)) {
                    hasKeyword = true;
                    break;
                }
            }
            if (hasKeyword || hasStrongNumericSignal(cleaned)) {
                target.add(cleaned);
            }
        }
    }

    private boolean hasStrongNumericSignal(String line) {
        String digitsOnly = line.replaceAll("\\D", "");
        return digitsOnly.length() >= 11;
    }

    private String extractFooterTextFastVariants(BufferedImage footerImage) {
        StringBuilder all = new StringBuilder();

        OcrResult simple = performOcrWithConfig(footerImage, 0);
        if (simple != null && simple.getText() != null && !simple.getText().isBlank()) {
            all.append(simple.getText()).append('\n');
        }

        try {
            Mat footerMat = preprocessingService.bufferedImageToMat(footerImage);
            Mat lab = preprocessingService.labColorSpacePipeline(footerMat);
            BufferedImage labImage = preprocessingService.matToBufferedImage(lab);
            OcrResult labResult = performOcrWithConfig(labImage, 0);
            if (labResult != null && labResult.getText() != null && !labResult.getText().isBlank()) {
                all.append(labResult.getText());
            }
        } catch (Exception e) {
            log.debug("Variant LAB footer ignorée: {}", e.getMessage());
        }

        return all.toString();
    }

    @Deprecated
    private BufferedImage cropFooterZone(BufferedImage image) {
        int height = image.getHeight();
        int footerHeight = (int) (height * 0.15); // Derniers 15%
        int startY = height - footerHeight;

        log.debug("Crop footer: {}x{} → zone {}x{} (y={} à {})",
                image.getWidth(), height,
                image.getWidth(), footerHeight,
                startY, height);

        return image.getSubimage(0, startY, image.getWidth(), footerHeight);
    }

}


