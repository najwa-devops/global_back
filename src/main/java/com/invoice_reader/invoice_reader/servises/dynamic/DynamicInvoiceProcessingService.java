package com.invoice_reader.invoice_reader.servises.dynamic;

import com.invoice_reader.invoice_reader.dto.account_tier.TierDto;
import com.invoice_reader.invoice_reader.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate;
import com.invoice_reader.invoice_reader.entity.account_tier.Tier;
import com.invoice_reader.invoice_reader.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.entity.template.SignatureType;
import com.invoice_reader.invoice_reader.entity.template.TemplateSignature;
import com.invoice_reader.invoice_reader.repository.DossierDao;
import com.invoice_reader.invoice_reader.repository.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import com.invoice_reader.invoice_reader.entity.dynamic.DocumentType;
import com.invoice_reader.invoice_reader.entity.dynamic.DuplicateLevel;
import com.invoice_reader.invoice_reader.servises.FileStorageService;
import com.invoice_reader.invoice_reader.servises.account_tier.TierService;
import com.invoice_reader.invoice_reader.servises.compat.MiniCompatibilityScanService;
import com.invoice_reader.invoice_reader.servises.ocr.AdvancedOcrService;
import com.invoice_reader.invoice_reader.servises.ocr.AmountValidatorService;
import com.invoice_reader.invoice_reader.servises.ocr.BusinessValidationService;
import com.invoice_reader.invoice_reader.servises.ocr.CommonInvoiceOcrData;
import com.invoice_reader.invoice_reader.servises.ocr.CommonInvoiceOcrService;
import com.invoice_reader.invoice_reader.servises.ocr.DocumentClassifierService;
import com.invoice_reader.invoice_reader.servises.ocr.DuplicateDetectionService;
import com.invoice_reader.invoice_reader.servises.ocr.OlmocrFallbackService;
import com.invoice_reader.invoice_reader.servises.ocr.TextCleaningService;
import com.invoice_reader.invoice_reader.servises.patterns.FieldPatternService;
import com.invoice_reader.invoice_reader.utils.ExtractionPatterns;
import com.invoice_reader.invoice_reader.utils.AmountToWordsFormatter;
import com.invoice_reader.invoice_reader.utils.InvoiceTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service de traitement des factures dynamiques
 * VERSION CORRIGÃƒâ€°E:
 * - DÃƒÂ©tection ICE/IF/RC UNIQUEMENT dans le FOOTER (75% du bas)
 * - Auto-crÃƒÂ©ation template DÃƒâ€°SACTIVÃƒâ€°E
 * - VÃƒÂ©rification tier existant avant template
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DynamicInvoiceProcessingService {

    private final DynamicTemplateService dynamicTemplateService;
    private final DynamicFieldExtractorService dynamicFieldExtractorService;
    private final AlphaAgentExtractionService alphaAgentExtractionService;
    private final DynamicInvoiceDao dynamicInvoiceDao;
    private final DossierDao dossierDao;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;
    private final TierService tierService;
    private final AdvancedOcrService advancedOcrService;
    private final FileStorageService fileStorageService;
    private final FieldPatternService fieldPatternService;
    // OCR UPGRADE — nouveaux services
    private final TextCleaningService textCleaningService;
    private final CommonInvoiceOcrService commonInvoiceOcrService;
    private final BusinessValidationService businessValidationService;
    private final DocumentClassifierService documentClassifierService;
    private final AmountValidatorService amountValidatorService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final OlmocrFallbackService olmocrFallbackService;
    private final MiniCompatibilityScanService miniCompatibilityScanService;

    @Value("${olmocr.strategy:fallback}")
    private String olmocrStrategy;
    @Value("${olmocr.primary-min-length:120}")
    private int olmocrPrimaryMinLength;

    // CONFIGURATION: Pourcentage du texte considÃƒÂ©rÃƒÂ© comme footer
    private static final double FOOTER_START_PERCENTAGE = 0.75; // Footer commence ÃƒÂ  60%

    @Transactional
    public DynamicInvoice processInvoice(MultipartFile file, Long dossierId) throws IOException {
        return processInvoice(file, dossierId, ExtractionEngine.DEFAULT);
    }

    @Transactional
    public DynamicInvoice processInvoice(MultipartFile file, Long dossierId, ExtractionEngine engine) throws IOException {
        log.info("=== DÃƒâ€°BUT TRAITEMENT FACTURE ===");
        log.info("Fichier: {}", file.getOriginalFilename());

        // Ãƒâ€°TAPE 1: STOCKAGE FICHIER
        String filePath = fileStorageService.store(file);
        Path path = Paths.get(filePath);
        log.info("Fichier stockÃƒÂ©: {}", filePath);

        // ÉTAPE 2: OCR — use mini-compatible OCR path (same backend strategy as mini)
        log.info("Lancement OCR...");
        String ocrText;
        String cleanedOcrText;
        boolean scanned;
        Map<String, Object> ocrMetadata = new HashMap<>();
        try {
            MiniCompatibilityScanService.OcrPayload miniOcrPayload =
                    engine == ExtractionEngine.ALPHA_AGENT
                            ? miniCompatibilityScanService.extractPurchaseAlphaOcrOnly(file)
                            : miniCompatibilityScanService.extractPurchaseOcrOnly(file);
            ocrText = miniOcrPayload.rawText() != null ? miniOcrPayload.rawText() : "";
            cleanedOcrText = miniOcrPayload.cleanedText() != null ? miniOcrPayload.cleanedText() : "";
            scanned = miniOcrPayload.scanned();
            if (miniOcrPayload.telemetry() != null) {
                ocrMetadata.putAll(miniOcrPayload.telemetry());
            }
            ocrMetadata.put("ocrEngineActive", engine == ExtractionEngine.ALPHA_AGENT
                    ? "mini_compatible_alpha"
                    : "mini_compatible_scan");
        } catch (Exception miniOcrError) {
            log.warn("Mini-compatible OCR path failed, fallback to existing path: {}", miniOcrError.getMessage());
            CommonInvoiceOcrData commonOcrData = commonInvoiceOcrService.analyze(path);
            ocrText = commonOcrData.rawText() != null ? commonOcrData.rawText() : "";
            cleanedOcrText = commonOcrData.cleanedText();
            scanned = commonOcrData.scanned();
            ocrMetadata.putAll(commonOcrData.toMetadataMap());
            ocrMetadata.put("ocrEngineActive", "common_ocr_fallback");
        }

        if (ocrText.isBlank()) {
            log.warn("OCR echoue: Aucun texte extrait pour {}", file.getOriginalFilename());
        }
        log.info("OCR termine: {} caracteres", ocrText.length());

        // ÉTAPE 3: CRÉATION ENTITY INVOICE
        DynamicInvoice invoice = new DynamicInvoice();

        // Métadonnées fichier
        invoice.setFilename(file.getOriginalFilename());
        invoice.setOriginalName(file.getOriginalFilename());
        invoice.setFilePath(filePath);
        invoice.setFileSize(file.getSize());

        invoice.setRawOcrText(ocrText);
        invoice.setScanned(scanned);
        invoice.setCleanedOcrText(cleanedOcrText);

        // Keep olmo fallback optional and only for non-alpha path.
        if (engine != ExtractionEngine.ALPHA_AGENT && !"primary".equalsIgnoreCase(olmocrStrategy)) {
            applyOlmocrFallback(path, invoice, ocrMetadata);
        }
        invoice.setExtractedData(ocrMetadata);

        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setDossierId(dossierId);

        // Ãƒâ€°TAPE 4 ÃƒÂ  9: TRAITEMENT COMMUN
        return applyProcessingRules(invoice, engine);
    }

    @Transactional
    public DynamicInvoice reprocessExistingInvoice(DynamicInvoice invoice) throws IOException {
        log.info("=== RETRAITMENT FACTURE EXISTANTE: {} ===", invoice.getId());

        if (invoice.getFilePath() == null) {
            throw new IllegalArgumentException("Chemin de fichier manquant pour la facture " + invoice.getId());
        }

        Path path = Paths.get(invoice.getFilePath());
        if (!Files.exists(path)) {
            throw new IOException("Fichier introuvable sur le disque: " + invoice.getFilePath());
        }

        // 1. RE-RUN OCR --- olmOCR en priorite si active + PDF, sinon Tesseract
        log.info("Lancement OCR (Retraitement)...");
        String ocrText;
        String cleanedOcrText;
        boolean scanned;
        Map<String, Object> ocrMetadata = new HashMap<>();
        if (invoice.getExtractedData() != null) {
            ocrMetadata.putAll(invoice.getExtractedData());
        }

        boolean tryOlmOcrFirst = "primary".equalsIgnoreCase(olmocrStrategy)
                && olmocrFallbackService.isEnabled()
                && path.toString().toLowerCase().endsWith(".pdf");

        if (tryOlmOcrFirst) {
            log.info("Mode olmOCR primary active pour retraitement PDF");
            ocrMetadata.put("olmocrPrimaryAttempted", true);
            Optional<OlmocrFallbackService.OlmocrResult> olmResult =
                    olmocrFallbackService.runAsPrimary(path, ocrMetadata);
            String olmText = olmResult.map(r -> textCleaningService.clean(r.markdownText())).orElse("");

            if (!olmText.isBlank() && olmText.length() >= olmocrPrimaryMinLength) {
                ocrText        = olmResult.get().markdownText();
                cleanedOcrText = olmText;
                scanned        = true;
                ocrMetadata.put("olmocrPrimaryAccepted", true);
                ocrMetadata.put("olmocrUsed", true);
                ocrMetadata.put("olmocrDurationMs",   olmResult.get().durationMs());
                ocrMetadata.put("olmocrMode",         olmResult.get().mode());
                ocrMetadata.put("olmocrReasons",      olmResult.get().reasons());
                ocrMetadata.put("olmocrMarkdownPath", olmResult.get().markdownPath());
                ocrMetadata.put("olmocrTextLength",   ocrText.length());
                ocrMetadata.put("ocrEngineActive",    "olmocr_primary");
            } else {
                log.warn("olmOCR primary insuffisant ({} chars), retour Tesseract", olmText.length());
                ocrMetadata.put("olmocrPrimaryAccepted", false);
                ocrMetadata.put("olmocrPrimaryRejectReason", olmText.isBlank() ? "empty" : "too short");
                ocrMetadata.put("olmocrPrimaryFallbackToCommon", true);
                CommonInvoiceOcrData commonOcrData = commonInvoiceOcrService.analyze(path);
                ocrText        = commonOcrData.rawText() != null ? commonOcrData.rawText() : "";
                cleanedOcrText = commonOcrData.cleanedText();
                scanned        = commonOcrData.scanned();
                ocrMetadata.putAll(commonOcrData.toMetadataMap());
                ocrMetadata.put("ocrEngineActive", "common_ocr_fallback");
            }
        } else {
            CommonInvoiceOcrData commonOcrData = commonInvoiceOcrService.analyze(path);
            ocrText        = commonOcrData.rawText() != null ? commonOcrData.rawText() : "";
            cleanedOcrText = commonOcrData.cleanedText();
            scanned        = commonOcrData.scanned();
            ocrMetadata.putAll(commonOcrData.toMetadataMap());
        }

        if (ocrText.isBlank()) {
            log.warn("OCR echoue lors du retraitement pour facture {}", invoice.getId());
        }

        // 2. Mettre a jour les donnees OCR
        invoice.setRawOcrText(ocrText);
        invoice.setScanned(scanned);
        invoice.setCleanedOcrText(cleanedOcrText);

        if (!"primary".equalsIgnoreCase(olmocrStrategy)) {
            applyOlmocrFallback(path, invoice, ocrMetadata);
        }
        ocrMetadata.put("lastReprocessedAt", LocalDateTime.now());
        invoice.setExtractedData(ocrMetadata);

        // 3. RÃƒÂ©initialiser status et champs
        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setUpdatedAt(LocalDateTime.now());
        // On ne vide pas fieldsData tout de suite, ils seront ÃƒÂ©crasÃƒÂ©s/fusionnÃƒÂ©s par
        // applyProcessingRules

        // 4. Lancer le pipeline
        return applyProcessingRules(invoice, ExtractionEngine.DEFAULT);
    }

    public Map<String, Object> extractSingleField(DynamicInvoice invoice, String fieldName) {
        if (invoice == null) {
            throw new IllegalArgumentException("Facture introuvable");
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName est requis");
        }
        String ocrText = invoice.getCleanedOcrText() != null && !invoice.getCleanedOcrText().isBlank()
                ? invoice.getCleanedOcrText()
                : invoice.getRawOcrText();
        if (ocrText == null || ocrText.isBlank()) {
            throw new IllegalArgumentException("Texte OCR non disponible pour cette facture");
        }

        DynamicExtractionResult extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
        DynamicExtractionResult.ExtractedField extractedField = extractionResult != null
                && extractionResult.getExtractedFields() != null
                ? extractionResult.getExtractedFields().get(fieldName)
                : null;
        if (extractedField == null || extractedField.getNormalizedValue() == null
                || String.valueOf(extractedField.getNormalizedValue()).isBlank()) {
            throw new IllegalArgumentException("Champ non detecte: " + fieldName);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", extractedField.getNormalizedValue());
        result.put("confidence", extractedField.getConfidence());
        result.put("detectionMethod", extractedField.getDetectionMethod());
        result.put("validated", extractedField.getValidated());
        result.put("validationError", extractedField.getValidationError());
        return result;
    }

    private DynamicInvoice applyProcessingRules(DynamicInvoice invoice, ExtractionEngine engine) {
        // OCR UPGRADE: utiliser le texte nettoyé pour l'extraction (raw conservé pour audit)
        String ocrText = invoice.getCleanedOcrText() != null && !invoice.getCleanedOcrText().isBlank()
                ? invoice.getCleanedOcrText()
                : invoice.getRawOcrText();

        // Ãƒâ€°TAPE 4: DÃƒâ€°TECTION SIGNATURE (IF/ICE/RC dans footer)
        log.info("DÃƒÂ©tection signature...");
        TemplateSignature signature = detectSignatureFromFooter(ocrText);
        invoice.setDetectedSignature(signature);

        if (signature != null) {
            log.info("Signature dÃƒÂ©tectÃƒÂ©e: {} = {}",
                    signature.getSignatureType(), signature.getSignatureValue());
        } else {
            log.warn("Aucune signature dÃƒÂ©tectÃƒÂ©e (IF/ICE/RC)");
        }

        // Ãƒâ€°TAPE 5: RECHERCHE TEMPLATE
        Optional<DynamicTemplate> templateOpt = dynamicTemplateService.detectTemplateBySignature(ocrText);
        DynamicTemplate template = null;

        if (templateOpt.isPresent()) {
            template = templateOpt.get();
            invoice.setTemplateId(template.getId());
            invoice.setTemplateName(template.getTemplateName());
            log.info("Template trouvÃƒÂ©: {} (ID={})", template.getTemplateName(), template.getId());
        } else {
            log.warn("Aucun template trouvÃƒÂ©");
        }

        // Ãƒâ€°TAPE 6: EXTRACTION CHAMPS
        log.info("Extraction des champs...");
        Map<String, Object> fieldsData = new LinkedHashMap<>();
        List<String> autoFilledFields = new ArrayList<>();

        DynamicExtractionResult extractionResult;
        if (engine == ExtractionEngine.ALPHA_AGENT) {
            extractionResult = alphaAgentExtractionService.extract(ocrText, template);
            fieldsData.putAll(extractionResult.toSimpleMap());
            log.info("Extraction mode ALPHA_AGENT activé");
        } else if (template != null) {
            // Extraction avec template
            extractionResult = dynamicFieldExtractorService.extractWithTemplate(ocrText, template);
            fieldsData.putAll(extractionResult.toSimpleMap());
        } else {
            // Extraction sans template
            extractionResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
            fieldsData.putAll(extractionResult.toSimpleMap());
        }

        // Auto-fill donnÃƒÂ©es fixes du template
        if (template != null && template.getFixedSupplierData() != null) {
            DynamicTemplate.FixedSupplierData fixed = template.getFixedSupplierData();

            if (fixed.getIce() != null && !fixed.getIce().isBlank()) {
                fieldsData.put("ice", fixed.getIce());
                autoFilledFields.add("ice");
            }
            if (fixed.getIfNumber() != null && !fixed.getIfNumber().isBlank()) {
                fieldsData.put("ifNumber", fixed.getIfNumber());
                autoFilledFields.add("ifNumber");
            }
            if (fixed.getRcNumber() != null && !fixed.getRcNumber().isBlank()) {
                fieldsData.put("rcNumber", fixed.getRcNumber());
                autoFilledFields.add("rcNumber");
            }
            if (fixed.getSupplier() != null && !fixed.getSupplier().isBlank()) {
                fieldsData.put("supplier", fixed.getSupplier());
                autoFilledFields.add("supplier");
            }
        }

        // Fallback: utiliser field_patterns pour les champs vides restants (y compris invoiceDate).
        applyMissingFieldsFallback(ocrText, fieldsData, template, extractionResult);

        // Vérifier la période d'exercice (si configurée)
        boolean withinExercisePeriod = validateInvoiceDateWithinExercise(invoice, fieldsData);

        // RÈGLE ICE DOSSIER (ACHAT): l'ICE fournisseur extrait doit être différent de l'ICE du dossier.
        String dossierIceWarning = evaluateDossierIceRule(invoice.getDossierId(), fieldsData, true);

        // ÉTAPE 7: LIAISON TIER AUTOMATIQUE
        log.info("Liaison Tier automatique...");
        linkInvoiceToTier(invoice, fieldsData, autoFilledFields);

        // Ãƒâ€°TAPE 8: CALCUL MONTANTS
        calculateAndValidateAmounts(fieldsData);

        // ETAPE 8B: DETECTION AVOIR
        invoice.setIsAvoir(InvoiceTypeDetector.isAvoir(fieldsData, invoice.getRawOcrText()));

        // OCR UPGRADE — ETAPE 8C: CLASSIFICATION DOCUMENT
        DocumentType docType = documentClassifierService.classify(ocrText);
        invoice.setDocumentType(docType);
        if (docType == DocumentType.AVOIR && !Boolean.TRUE.equals(invoice.getIsAvoir())) {
            invoice.setIsAvoir(true); // sync avec le flag existant
        }

        // OCR UPGRADE — ETAPE 8D: VALIDATION MONTANTS (tolérance 5 centimes)
        boolean amountsValid = amountValidatorService.applyToFieldsData(fieldsData);
        invoice.setAmountsValid(amountsValid);
        String validationMessage = amountsValid ? null : "Incohérence TTC ≠ HT+TVA (>0.05 DH)";
        if (!withinExercisePeriod) {
            validationMessage = appendValidationMessage(validationMessage,
                    "Facture hors période d'exercice - contrôle manuel requis.");
        }
        validationMessage = appendValidationMessage(validationMessage, dossierIceWarning);
        invoice.setValidationMessage(validationMessage);

        BusinessValidationService.ValidationOutcome validationOutcome =
                businessValidationService.validate(fieldsData, ocrText, false);
        List<String> mergedReviewReasons = new ArrayList<>(validationOutcome.anomalies());
        boolean mergedReviewRequired = validationOutcome.reviewRequired();
        if (dossierIceWarning != null && !dossierIceWarning.isBlank()) {
            mergedReviewRequired = true;
            mergedReviewReasons.add(dossierIceWarning);
        }
        invoice.getExtractedData().put("coherenceScore", validationOutcome.coherenceScore());
        invoice.getExtractedData().put("validationAnomalies", mergedReviewReasons);
        invoice.getExtractedData().put("fieldConfidences", validationOutcome.fieldConfidences());
        invoice.getExtractedData().put("fieldSources", validationOutcome.fieldSources());
        invoice.getExtractedData().put("reviewRequired", mergedReviewRequired);
        invoice.getExtractedData().put("reviewReasons", mergedReviewReasons);
        invoice.getExtractedData().put("extractionEngine", engine.name());

        String extractionMethod = resolveExtractionMethod(engine, template);
        fieldsData.put("extractionMethod", extractionMethod);
        fieldsData.put("overallConfidence", extractionResult.getOverallConfidence());
        fieldsData.put("missingFields", computeMissingCoreFields(fieldsData));
        fieldsData.put("lowConfidenceFields", extractionResult.getLowConfidenceFields());
        invoice.getExtractedData().put("extractionMethod", extractionMethod);

        // Ãƒâ€°TAPE 9: FINALISATION
        invoice.setFieldsData(fieldsData);
        invoice.setAutoFilledFields(autoFilledFields);
        List<String> lowConfidenceFields = extractionResult.getLowConfidenceFields() != null
                ? new ArrayList<>(extractionResult.getLowConfidenceFields())
                : new ArrayList<>();
        lowConfidenceFields.addAll(validationOutcome.weakFields());
        invoice.setLowConfidenceFields(new ArrayList<>(new LinkedHashSet<>(lowConfidenceFields)));
        invoice.setMissingFields(computeMissingCoreFields(fieldsData));
        invoice.setOverallConfidence(extractionResult.getOverallConfidence());

        // DÃƒÂ©terminer status (Decision Matrix du Workflow)
        boolean totalsRecalculated = Boolean.TRUE.equals(fieldsData.get("totalsCalculated"));
        if (invoice.getRawOcrText().isBlank()) {
            invoice.setStatus(InvoiceStatus.ERROR);
            log.warn("Status: ERROR (Texte OCR vide)");
        } else if (!withinExercisePeriod) {
            invoice.setStatus(InvoiceStatus.OUT_OF_PERIOD);
            log.warn("Status: OUT_OF_PERIOD (Date hors exercice)");
        } else if (invoice.getTier() != null && invoice.getTemplateId() != null && extractionResult.isComplete()) {
            invoice.setStatus(InvoiceStatus.READY_TO_VALIDATE);
            log.info("Status: READY_TO_VALIDATE");
        } else if (totalsRecalculated) {
            invoice.setStatus(InvoiceStatus.RECALCULATED);
            log.info("Status: RECALCULATED (Montants recalculés)");
        } else {
            invoice.setStatus(InvoiceStatus.TREATED);
            log.info("Status: TREATED (Manque Tier, Template ou champs requis)");
        }

        // Ãƒâ€°TAPE 10: SAUVEGARDE
        DynamicInvoice saved = dynamicInvoiceDao.save(invoice);

        // OCR UPGRADE — ETAPE 10B: DETECTION DOUBLONS (nécessite id persisté)
        try {
            DuplicateDetectionService.DetectionResult dupResult = duplicateDetectionService.detect(saved);
            if (dupResult.level() != DuplicateLevel.NONE) {
                saved.setDuplicateLevel(dupResult.level());
                saved.setDuplicateOfId(dupResult.duplicateOfId());
                if (saved.getStatus() != InvoiceStatus.VALIDATED) {
                    saved.setStatus(InvoiceStatus.DUPLICATE);
                }
                saved = dynamicInvoiceDao.save(saved);
                log.warn("Doublon détecté: niveau={}, doublon de id={}", dupResult.level(), dupResult.duplicateOfId());
            }
        } catch (Exception e) {
            log.debug("Détection doublons ignorée: {}", e.getMessage());
        }

        log.info("=== FIN TRAITEMENT FACTURE ===");
        log.info("Invoice ID: {}", saved.getId());
        log.info("Status: {}", saved.getStatus());
        log.info("Template: {}", saved.getTemplateName() != null ? saved.getTemplateName() : "Aucun");
        log.info("Tier: {}", saved.getTierName() != null ? saved.getTierName() : "Aucun");

        return saved;
    }

    private String resolveExtractionMethod(ExtractionEngine engine, DynamicTemplate template) {
        if (engine == ExtractionEngine.ALPHA_AGENT) {
            return "ALPHA_AGENT";
        }
        return template != null ? "DYNAMIC_TEMPLATE" : "PATTERNS";
    }

    private boolean validateInvoiceDateWithinExercise(DynamicInvoice invoice, Map<String, Object> fieldsData) {
        Long dossierId = invoice.getDossierId();
        if (dossierId == null) {
            return true;
        }
        Dossier dossier = dossierDao.findById(dossierId).orElse(null);
        if (dossier == null || dossier.getExerciseStartDate() == null || dossier.getExerciseEndDate() == null) {
            return true;
        }
        String rawDate = getStringValue(fieldsData, "invoiceDate");
        LocalDate invoiceDate = parseInvoiceDate(rawDate);
        if (invoiceDate == null) {
            return true;
        }
        if (invoiceDate.isBefore(dossier.getExerciseStartDate())
                || invoiceDate.isAfter(dossier.getExerciseEndDate())) {
            fieldsData.put("exercisePeriodStatus", "OUT_OF_PERIOD");
            fieldsData.put("exerciseStartDate", dossier.getExerciseStartDate());
            fieldsData.put("exerciseEndDate", dossier.getExerciseEndDate());
            fieldsData.put("invoiceDate", invoiceDate);
            fieldsData.put("exercisePeriodWarning", String.format(
                    "Facture hors période d'exercice (du %s au %s). Date facture: %s",
                    dossier.getExerciseStartDate(),
                    dossier.getExerciseEndDate(),
                    invoiceDate));
            return false;
        }
        fieldsData.put("exercisePeriodStatus", "OK");
        return true;
    }

    private void applyOlmocrFallback(Path path, DynamicInvoice invoice, Map<String, Object> ocrMetadata) {
        // Uniquement strategy=fallback - primary est gere en amont dans processInvoice
        olmocrFallbackService.runIfNeeded(path, ocrMetadata).ifPresent(result -> {
            String mergedText = mergeOcrTexts(invoice.getCleanedOcrText(),
                    textCleaningService.clean(result.markdownText()));
            invoice.setCleanedOcrText(mergedText);
            ocrMetadata.put("olmocrUsed", true);
            ocrMetadata.put("olmocrDurationMs",   result.durationMs());
            ocrMetadata.put("olmocrMode",         result.mode());
            ocrMetadata.put("olmocrReasons",      result.reasons());
            ocrMetadata.put("olmocrMarkdownPath", result.markdownPath());
            ocrMetadata.put("olmocrTextLength",   result.markdownText().length());
            ocrMetadata.put("ocrEngineActive",    "common_plus_olmocr_fallback");
        });
    }

    private String mergeOcrTexts(String primaryText, String fallbackText) {
        String primary = primaryText != null ? primaryText.trim() : "";
        String fallback = fallbackText != null ? fallbackText.trim() : "";
        if (fallback.isBlank()) {
            return primary;
        }
        if (primary.isBlank()) {
            return fallback;
        }
        if (fallback.length() > (primary.length() * 1.15)) {
            return fallback;
        }
        if (primary.contains("[HEADER]") || primary.contains("[FOOTER]")) {
            return primary + "\n\n[OLMOCR]\n" + fallback;
        }
        return primary + "\n\n" + fallback;
    }

    private LocalDate parseInvoiceDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int spaceIdx = value.indexOf(' ');
        if (spaceIdx > 0) {
            value = value.substring(0, spaceIdx);
        }
        int timeIdx = value.indexOf('T');
        if (timeIdx > 0) {
            value = value.substring(0, timeIdx);
        }
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("dd/MM/yy"),
                DateTimeFormatter.ofPattern("dd-MM-yy")
        );
        for (DateTimeFormatter formatter : formats) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ===================== DÃƒâ€°TECTION SIGNATURE (FOOTER UNIQUEMENT)
    // =====================

    /**
     * DÃƒÂ©tecte la signature du fournisseur UNIQUEMENT dans le footer (60% du bas)
     * CRITIQUE: Ignore le header pour ÃƒÂ©viter de dÃƒÂ©tecter l'ICE du client
     */
    private TemplateSignature detectSignatureFromFooter(String text) {
        log.info("=== DETECTION SIGNATURES DANS LE FOOTER ===");

        String footer = extractFooter(text);
        log.info("Footer extrait: {} caracteres ({}% du bas)",
                footer.length(), (int) ((1 - FOOTER_START_PERCENTAGE) * 100));

        // Extraire TOUTES les signatures
        String ifNumber = extractIfFromFooter(footer);
        String ice = extractIceFromFooter(footer);
        String rc = extractRcFromFooter(footer);

        // Log results
        log.info("RÃƒÂ©sultats extraction footer: IF={}, ICE={}, RC={}", ifNumber, ice, rc);

        // Logger toutes les signatures dÃƒÂ©tectÃƒÂ©es
        log.info("Signatures detectees dans footer:");
        if (ifNumber != null)
            log.info("  - IF: {}", ifNumber);
        if (ice != null)
            log.info("  - ICE: {}", ice);
        if (rc != null)
            log.info("  - RC: {}", rc);

        // PRIORITÃƒâ€° 1: IF (plus fiable - unique)
        if (ifNumber != null && ifNumber.matches("\\d{7,10}")) {
            log.info("Signature retenue: IF:{}", ifNumber);
            return new TemplateSignature(SignatureType.IF, ifNumber);
        }

        // PRIORITÃƒâ€° 2: ICE (fallback)
        if (ice != null && ice.matches("\\d{15}")) {
            log.info("Signature retenue: ICE:{}", ice);
            return new TemplateSignature(SignatureType.ICE, ice);
        }

        // PRIORITÃƒâ€° 3: RC (Nouveau fallback)
        if (rc != null && !rc.isBlank()) {
            log.info("Signature retenue: RC:{}", rc);
            return new TemplateSignature(SignatureType.RC, rc);
        }

        log.warn("Aucune signature valide detectee");
        return null;
    }

    /**
     * Extrait le footer (40% du bas du texte, commenÃƒÂ§ant ÃƒÂ  60%)
     */
    /**
     * Extrait le footer en utilisant le marqueur [FOOTER] ou par pourcentage
     * (fallback)
     */
    private String extractFooter(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Ã¢Å“â€¦ AMÃƒâ€°LIORATION: Utiliser le marqueur de zone si prÃƒÂ©sent
        if (text.contains("[FOOTER]")) {
            int footerIndex = text.indexOf("[FOOTER]");
            String footer = text.substring(footerIndex + "[FOOTER]".length()).trim();
            log.debug("Footer extrait via marqueur [FOOTER] ({} chars)", footer.length());
            return footer;
        }

        // Fallback par pourcentage
        int footerStartIndex = (int) (text.length() * FOOTER_START_PERCENTAGE);
        String footer = text.substring(footerStartIndex);

        log.debug("Footer extrait via pourcentage {}% ({} chars)",
                (int) (FOOTER_START_PERCENTAGE * 100), footer.length());

        return footer;
    }

    /**
     * Extrait l'ICE UNIQUEMENT dans le footer
     * IMPORTANT: Prend le DERNIER ICE trouvÃƒÂ© (= fournisseur, pas client)
     */
    private String extractIceFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche ICE dans le footer ({} caracteres)", footer.length());

        List<String> allIceFound = new ArrayList<>();

        for (String patternStr : ExtractionPatterns.ICE_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                String ice = ExtractionPatterns.cleanNumber(matcher.group(1));
                if (ice.length() == 15 && ice.matches("\\d{15}")) {
                    allIceFound.add(ice);
                    log.debug("ICE candidat trouve: {}", ice);
                }
            }
        }

        if (!allIceFound.isEmpty()) {
            String lastIce = allIceFound.get(allIceFound.size() - 1);

            log.info("=== DETECTION ICE FINALE ===");
            log.info("Total ICE trouves dans le footer: {}", allIceFound.size());

            if (allIceFound.size() > 1) {
                log.warn("ATTENTION: Plusieurs ICE detectes:");
                for (int i = 0; i < allIceFound.size(); i++) {
                    String ice = allIceFound.get(i);
                    if (i == allIceFound.size() - 1) {
                        log.warn("  ICE #{}: {} <- FOURNISSEUR (RETENU)", i + 1, ice);
                    } else {
                        log.warn("  ICE #{}: {} <- Possiblement CLIENT (IGNORE)", i + 1, ice);
                    }
                }
            } else {
                log.info("ICE FOURNISSEUR unique: {}", lastIce);
            }

            return lastIce;
        }

        log.debug("Aucun ICE trouve dans le footer");
        return null;
    }

    /**
     * Extrait l'IF UNIQUEMENT dans le footer
     * IMPORTANT: Prend le DERNIER IF trouvÃƒÂ© (= fournisseur, pas client)
     */
    private String extractIfFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche IF dans le footer ({} caracteres)", footer.length());

        List<String> allIfFound = new ArrayList<>();

        for (String patternStr : ExtractionPatterns.IF_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                String ifNum = ExtractionPatterns.cleanNumber(matcher.group(1));
                if (ifNum.matches("\\d{7,10}")) {
                    allIfFound.add(ifNum);
                    log.debug("IF candidat trouve: {}", ifNum);
                }
            }
        }

        if (!allIfFound.isEmpty()) {
            String lastIf = allIfFound.get(allIfFound.size() - 1);

            log.info("=== DETECTION IF FINALE ===");
            log.info("Total IF trouves dans le footer: {}", allIfFound.size());

            if (allIfFound.size() > 1) {
                log.warn("ATTENTION: Plusieurs IF detectes:");
                for (int i = 0; i < allIfFound.size(); i++) {
                    String ifNum = allIfFound.get(i);
                    if (i == allIfFound.size() - 1) {
                        log.warn("  IF #{}: {} <- FOURNISSEUR (RETENU)", i + 1, ifNum);
                    } else {
                        log.warn("  IF #{}: {} <- IGNORE", i + 1, ifNum);
                    }
                }
            } else {
                log.info("IF FOURNISSEUR unique: {}", lastIf);
            }

            return lastIf;
        }

        log.debug("Aucun IF trouve dans le footer");
        return null;
    }

    /**
     * Extrait le RC UNIQUEMENT dans le footer
     */
    private String extractRcFromFooter(String footer) {
        if (footer == null || footer.isEmpty()) {
            return null;
        }

        log.debug("Recherche RC dans le footer ({} caracteres)", footer.length());

        String lastRc = null;

        for (String patternStr : ExtractionPatterns.RC_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(footer);

            while (matcher.find()) {
                lastRc = matcher.group(1);
                log.debug("RC candidat trouve: {}", lastRc);
            }
        }

        if (lastRc != null) {
            log.info("RC FOURNISSEUR final: {}", lastRc);
        } else {
            log.debug("Aucun RC trouve dans le footer");
        }

        return lastRc;
    }

    // ===================== LIAISON TIER (IF prioritaire, puis ICE)
    // =====================

    /**
     * Lie automatiquement la facture ÃƒÂ  un tier existant
     * PRIORITÃƒâ€° 1: Recherche par IF (plus fiable)
     * PRIORITÃƒâ€° 2: Recherche par ICE (fallback)
     */
    private void linkInvoiceToTier(DynamicInvoice invoice, Map<String, Object> fieldsData,
            List<String> autoFilledFields) {
        log.info("=== RECHERCHE TIER POUR LIAISON AUTOMATIQUE ===");

        String extractedIf = getStringValue(fieldsData, "ifNumber");
        String extractedIce = getStringValue(fieldsData, "ice");
        Long dossierId = invoice.getDossierId();

        Tier tier = null;

        // PRIORITÃƒâ€° 1: Chercher par IF
        if (dossierId != null && extractedIf != null && !extractedIf.isBlank()) {
            Optional<TierDto> tierDto = tierService.getTierByIfNumber(extractedIf, dossierId);
            if (tierDto.isPresent()) {
                tier = convertDtoToEntity(tierDto.get());
                log.info("Tier trouve par IF: {} (IF: {})", tier.getLibelle(), extractedIf);
            }
        }

        // PRIORITÃƒâ€° 2: Chercher par ICE
        if (tier == null && dossierId != null && extractedIce != null && !extractedIce.isBlank()) {
            Optional<TierDto> tierDto = tierService.getTierByIce(extractedIce, dossierId);
            if (tierDto.isPresent()) {
                tier = convertDtoToEntity(tierDto.get());
                log.info("Tier trouve par ICE: {} (ICE: {})", tier.getLibelle(), extractedIce);
            }
        }

        // Si trouvÃƒÂ©, lier ÃƒÂ  la facture
        if (tier != null) {
            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            log.info("Facture liÃƒÂ©e au tier: ID={}, Nom={}", tier.getId(), tier.getLibelle());

            // NOUVEAU : Remplacer supplier par Tier.libelle
            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");
            log.info("Supplier remplacÃƒÂ© par Tier.libelle: {}", tier.getLibelle());

            if (tier.getActivity() != null && !tier.getActivity().isBlank()) {
                fieldsData.put("activity", tier.getActivity());
                autoFilledFields.add("activity");
            }

            // Auto-remplir les comptes comptables
            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getDefaultChargeAccount());
                fieldsData.put("tvaAccount", tier.getTvaAccount());
                fieldsData.put("tvaRate", tier.getDefaultTvaRate());
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("tvaRateUsed", tier.getDefaultTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");

                log.info("Comptes comptables auto-remplis depuis le Tier:");
                log.info("  - tierNumber: {}", tier.getTierNumber());
                log.info("  - chargeAccount: {}", tier.getDefaultChargeAccount());
                log.info("  - tvaAccount: {}", tier.getTvaAccount());
            }
        } else {
            log.warn("Aucun Tier trouve pour cette facture");
            log.warn("   IF: {}", extractedIf != null ? extractedIf : "non dÃƒÂ©tectÃƒÂ©");
            log.warn("   ICE: {}", extractedIce != null ? extractedIce : "non dÃƒÂ©tectÃƒÂ©");
            log.info("Ã¢â€ â€™ L'utilisateur devra crÃƒÂ©er ou lier un tier manuellement");
        }
    }

    // ===================== OCR =====================

    private String performOcr(File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            log.info("PDF detecte, conversion en image...");
            File imageFile = convertPdfToImage(file);
            try {
                return String.valueOf(advancedOcrService.extractTextAdvanced(imageFile.toPath()));
            } finally {
                if (imageFile != null && imageFile.exists()) {
                    imageFile.delete();
                }
            }
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png")) {
            return String.valueOf(advancedOcrService.extractTextAdvanced(file.toPath()));
        } else {
            throw new IllegalArgumentException("Type de fichier non supporte: " + fileName);
        }
    }

    private File convertPdfToImage(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("Le PDF ne contient aucune page");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 300);

            File tempFile = File.createTempFile("invoice_", ".png");
            ImageIO.write(image, "PNG", tempFile);

            log.info("PDF converti: {}x{} pixels", image.getWidth(), image.getHeight());
            return tempFile;
        }
    }

    // ===================== UTILS =====================

    private Double extractDouble(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null)
            return null;

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        try {
            String str = value.toString()
                    .replaceAll("[^0-9,.]", "")
                    .replace(",", ".");
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Tier convertDtoToEntity(TierDto dto) {
        return Tier.builder()
                .id(dto.getId())
                .auxiliaireMode(dto.getAuxiliaireMode())
                .tierNumber(dto.getTierNumber())
                .collectifAccount(dto.getCollectifAccount())
                .libelle(dto.getLibelle())
                .activity(dto.getActivity())
                .ifNumber(dto.getIfNumber())
                .ice(dto.getIce())
                .rcNumber(dto.getRcNumber())
                .defaultChargeAccount(dto.getDefaultChargeAccount())
                .tvaAccount(dto.getTvaAccount())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .active(dto.getActive())
                .build();
    }

    private void applyMissingFieldsFallback(
            String ocrText,
            Map<String, Object> fieldsData,
            DynamicTemplate template,
            DynamicExtractionResult extractionResult
    ) {
        if (ocrText == null || ocrText.isBlank() || fieldsData == null) {
            return;
        }

        Set<String> candidates = new LinkedHashSet<>();

        if (extractionResult != null && extractionResult.getMissingFields() != null) {
            candidates.addAll(extractionResult.getMissingFields());
        }

        if (template != null && template.getFieldDefinitions() != null) {
            template.getFieldDefinitions().stream()
                    .map(DynamicTemplate.DynamicFieldDefinitionJson::getFieldName)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
        }

        candidates.addAll(List.of(
                "invoiceNumber",
                "invoiceDate",
                "amountHT",
                "tva",
                "amountTTC",
                "ice",
                "ifNumber",
                "rcNumber",
                "supplier"
        ));

        Map<String, Object> heuristicFallback = Collections.emptyMap();
        try {
            DynamicExtractionResult heuristicResult = dynamicFieldExtractorService.extractWithoutTemplate(ocrText);
            if (heuristicResult != null) {
                heuristicFallback = heuristicResult.toSimpleMap();
            }
        } catch (Exception e) {
            log.warn("Fallback heuristique indisponible: {}", e.getMessage());
        }

        for (String fieldName : candidates) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }

            Object existing = fieldsData.get(fieldName);
            if (existing != null && !existing.toString().isBlank()) {
                continue;
            }

            Optional<String> dbMatch = fieldPatternService.extractFirstMatch(fieldName, ocrText);
            if (dbMatch.isPresent()) {
                String value = dbMatch.get();
                fieldsData.put(fieldName, value);
                log.info("Fallback field_patterns matched [{}] = {}", fieldName, value);
                continue;
            }

            Object heuristicValue = heuristicFallback.get(fieldName);
            if (heuristicValue != null && !heuristicValue.toString().isBlank()) {
                fieldsData.put(fieldName, heuristicValue);
                log.info("Fallback heuristique matched [{}] = {}", fieldName, heuristicValue);
            }
        }
    }

    private void calculateAndValidateAmounts(Map<String, Object> fieldsData) {
        log.info("=== CALCUL ET VALIDATION MONTANTS ===");
        boolean totalsCalculated = false;
        Set<String> computedAmountFields = new LinkedHashSet<>();

        List<Double> multiHt = parseMultiAmounts(fieldsData.get("htValues"));
        List<Double> multiTva = parseMultiAmounts(fieldsData.get("tvaValues"));
        if (multiHt.size() >= 2) {
            double htSum = round2(multiHt.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("amountHT", htSum);
            fieldsData.put("amountHTSource", "MULTI_LINE_SUM");
            computedAmountFields.add("amountHT");
            log.info("HT multi-lignes detecte -> amountHT recalcule: {}", htSum);
        }
        if (multiTva.size() >= 2) {
            double tvaSum = round2(multiTva.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("tva", tvaSum);
            fieldsData.put("tvaSource", "MULTI_LINE_SUM");
            computedAmountFields.add("tva");
            log.info("TVA multi-lignes detectee -> tva recalculee: {}", tvaSum);
        }

        Double amountHT = extractDouble(fieldsData, "amountHT");
        Double tva = extractDouble(fieldsData, "tva");
        Double amountTTC = extractDouble(fieldsData, "amountTTC");
        boolean hasMultiLineAmounts = multiHt.size() >= 2 || multiTva.size() >= 2;

        log.debug("Montants extraits: HT={}, TVA={}, TTC={}", amountHT, tva, amountTTC);

        if (hasMultiLineAmounts && amountHT != null && tva != null) {
            double calculatedFromLines = round2(amountHT + tva);
            if (amountTTC == null || Math.abs(amountTTC - calculatedFromLines) > 0.01) {
                fieldsData.put("amountTTC", calculatedFromLines);
                fieldsData.put("amountTTCSource", "MULTI_LINE_SUM");
                amountTTC = calculatedFromLines;
                totalsCalculated = true;
                computedAmountFields.add("amountTTC");
                log.info("TTC recalcule depuis montants multi-lignes: {}", calculatedFromLines);
            }
        }

        Double configuredTvaRate = extractDouble(fieldsData, "tvaRate");
        if (amountTTC != null && configuredTvaRate != null && configuredTvaRate >= 0.0 && configuredTvaRate <= 30.0) {
            if (Math.abs(configuredTvaRate) < 0.001) {
                if (amountHT == null) {
                    amountHT = round2(amountTTC);
                    fieldsData.put("amountHT", amountHT);
                    fieldsData.put("amountHTSource", "TTC_ZERO_RATE");
                    totalsCalculated = true;
                    computedAmountFields.add("amountHT");
                }
                if (tva == null) {
                    tva = 0.0;
                    fieldsData.put("tva", tva);
                    fieldsData.put("tvaSource", "TTC_ZERO_RATE");
                    totalsCalculated = true;
                    computedAmountFields.add("tva");
                }
                log.info("Montants deduits depuis TTC + taux 0%: HT={}, TVA={}", amountHT, tva);
            } else if (amountHT == null && tva == null) {
                double calculatedHT = round2(amountTTC / (1.0 + (configuredTvaRate / 100.0)));
                double calculatedTVA = round2(amountTTC - calculatedHT);
                amountHT = calculatedHT;
                tva = calculatedTVA;
                fieldsData.put("amountHT", calculatedHT);
                fieldsData.put("tva", calculatedTVA);
                fieldsData.put("amountHTSource", "TTC_TVA_RATE");
                fieldsData.put("tvaSource", "TTC_TVA_RATE");
                fieldsData.put("tvaRateUsed", configuredTvaRate);
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("amountsCalculationSource", "TTC_AND_TVA_RATE");
                totalsCalculated = true;
                computedAmountFields.add("amountHT");
                computedAmountFields.add("tva");
                log.info("Montants deduits depuis TTC + taux TVA {}%: HT={}, TVA={}",
                        configuredTvaRate, amountHT, tva);
            }
        }

        if (amountHT != null && tva != null) {
            Double calculatedTTC = round2(amountHT + tva);
            log.info("TTC calcule: {} (HT {} + TVA {})", calculatedTTC, amountHT, tva);

            if (amountTTC != null) {
                double difference = Math.abs(amountTTC - calculatedTTC);
                if (difference > 0.01) {
                    fieldsData.put("amountTTC", calculatedTTC);
                    fieldsData.put("amountTTCSource", "HT_TVA_SUM");
                    fieldsData.put("ttcDifference", difference);
                    totalsCalculated = true;
                    computedAmountFields.add("amountTTC");
                    log.info("TTC remplace par valeur calculee");
                }
            } else {
                fieldsData.put("amountTTC", calculatedTTC);
                fieldsData.put("amountTTCSource", "HT_TVA_SUM");
                totalsCalculated = true;
                computedAmountFields.add("amountTTC");
                log.info("TTC calcule automatiquement: {}", calculatedTTC);
            }
        }

        if (amountHT != null && amountTTC != null && tva == null) {
            Double calculatedTVA = round2(amountTTC - amountHT);
            fieldsData.put("tva", calculatedTVA);
            fieldsData.put("tvaSource", "TTC_MINUS_HT");
            totalsCalculated = true;
            computedAmountFields.add("tva");
            log.info("TVA calculee automatiquement: {}", calculatedTVA);
        }

        if (tva != null && amountTTC != null && amountHT == null) {
            Double calculatedHT = round2(amountTTC - tva);
            fieldsData.put("amountHT", calculatedHT);
            fieldsData.put("amountHTSource", "TTC_MINUS_TVA");
            totalsCalculated = true;
            computedAmountFields.add("amountHT");
            log.info("HT calcule automatiquement: {}", calculatedHT);
        }

        if (amountHT != null && tva != null) {
            double tvaRate = (tva / amountHT) * 100.0;
            fieldsData.put("calculatedTvaRate", Math.round(tvaRate * 100.0) / 100.0);
            log.info("Taux TVA calcule: {}%", tvaRate);

            if (configuredTvaRate == null) {
                double foundRate = 0.0;
                boolean standardRate = false;
                if (Math.abs(tvaRate - 20.0) < 0.5) {
                    foundRate = 20.0;
                    standardRate = true;
                } else if (Math.abs(tvaRate - 14.0) < 0.5) {
                    foundRate = 14.0;
                    standardRate = true;
                } else if (Math.abs(tvaRate - 10.0) < 0.5) {
                    foundRate = 10.0;
                    standardRate = true;
                } else if (Math.abs(tvaRate - 7.0) < 0.5) {
                    foundRate = 7.0;
                    standardRate = true;
                } else if (Math.abs(tvaRate) < 0.1) {
                    foundRate = 0.0;
                    standardRate = true;
                }

                if (standardRate) {
                    log.info("Taux TVA standard detecte: {}%", foundRate);
                    fieldsData.put("tvaRate", foundRate);
                    fieldsData.put("tvaRateSource", "OCR");
                } else {
                    log.warn("Taux TVA non standard: {}%", tvaRate);
                    fieldsData.put("hasValidationWarning", true);
                    fieldsData.put("validationWarningMessage",
                            "Taux TVA non standard detecte: " + Math.round(tvaRate) + "%");
                }
            } else {
                fieldsData.put("detectedTvaRate", Math.round(tvaRate * 100.0) / 100.0);
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("tvaRateUsed", configuredTvaRate);
            }
        }

        fieldsData.put("totalsCalculated", totalsCalculated);
        if (!computedAmountFields.isEmpty()) {
            fieldsData.put("computedAmountFields", new ArrayList<>(computedAmountFields));
            fieldsData.put("amountsCalculationSource", "OCR_AND_RULES");
        }
        syncAmountTtcEnLettres(fieldsData);
        log.info("=== FIN CALCUL MONTANTS ===");
    }

    private void syncAmountTtcEnLettres(Map<String, Object> fieldsData) {
        if (fieldsData == null) {
            return;
        }
        Object amountValue = fieldsData.get("amountTTC");
        if (amountValue == null) {
            amountValue = fieldsData.get("totalTtc");
        }
        if (amountValue == null) {
            amountValue = fieldsData.get("amountTTc");
        }
        String amountWords = AmountToWordsFormatter.formatTtcInWords(amountValue);
        if (!amountWords.isBlank()) {
            // Numeric TTC found → computed value is more reliable, override
            fieldsData.put("amountTTCEnLettres", amountWords);
        }
        // If amountTTC not found, preserve the OCR-extracted amountTTCEnLettres (never remove it)
    }

    private List<Double> parseMultiAmounts(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }

        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return Collections.emptyList();
        }

        List<Double> values = new ArrayList<>();
        String[] tokens = text.split("\\|");
        for (String token : tokens) {
            Double value = extractAmount(token);
            if (value != null && value > 0) {
                values.add(round2(value));
            }
        }
        return values;
    }

    private Double extractAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(",", ".")
                .replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> computeMissingCoreFields(Map<String, Object> fieldsData) {
        if (fieldsData == null) {
            return new ArrayList<>();
        }
        List<String> coreFields = Arrays.asList(
                "invoiceNumber",
                "invoiceDate",
                "amountHT",
                "tva",
                "amountTTC",
                "ice",
                "ifNumber",
                "rcNumber",
                "supplier");
        List<String> missing = new ArrayList<>();
        for (String key : coreFields) {
            Object value = fieldsData.get(key);
            if (value == null || value.toString().isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String evaluateDossierIceRule(Long dossierId, Map<String, Object> fieldsData, boolean purchaseFlow) {
        if (dossierId == null || fieldsData == null) {
            return null;
        }
        String extractedIce = normalizeIce(getStringValue(fieldsData, "ice"));
        if (extractedIce == null || extractedIce.isBlank()) {
            return null;
        }
        String dossierIce = dossierGeneralParamsDao.findByDossierId(dossierId)
                .map(params -> normalizeIce(params.getIce()))
                .orElse(null);
        if (dossierIce == null || dossierIce.isBlank()) {
            return null;
        }

        boolean matches = dossierIce.equals(extractedIce);
        if (purchaseFlow && matches) {
            fieldsData.put("iceRuleStatus", "PURCHASE_ICE_MATCHES_DOSSIER");
            return "ICE dossier identique à ICE facture achat: document probablement facture de vente.";
        }
        if (!purchaseFlow && !matches) {
            fieldsData.put("iceRuleStatus", "SALES_ICE_DIFFERS_FROM_DOSSIER");
            return "ICE dossier différent de l'ICE facture vente: document probablement facture d'achat.";
        }
        fieldsData.put("iceRuleStatus", "OK");
        return null;
    }

    private String normalizeIce(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\D", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String appendValidationMessage(String current, String extra) {
        if (extra == null || extra.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return extra;
        }
        return current + " | " + extra;
    }
}

    
    
        
    
    
    

