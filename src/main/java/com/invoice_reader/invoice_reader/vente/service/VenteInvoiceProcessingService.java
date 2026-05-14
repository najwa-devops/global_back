package com.invoice_reader.invoice_reader.vente.service;

import com.invoice_reader.invoice_reader.account_tier.dto.TierDto;
import com.invoice_reader.invoice_reader.database.entity.account_tier.Tier;
import com.invoice_reader.invoice_reader.database.entity.auth.Dossier;
import com.invoice_reader.invoice_reader.achat.entity.DocumentType;
import com.invoice_reader.invoice_reader.achat.entity.AchatTemplate;
import com.invoice_reader.invoice_reader.database.entity.account_tier.Account;
import com.invoice_reader.invoice_reader.database.entity.invoice.InvoiceStatus;
import com.invoice_reader.invoice_reader.database.entity.template.SignatureType;
import com.invoice_reader.invoice_reader.database.entity.template.TemplateSignature;
import com.invoice_reader.invoice_reader.database.dao.DossierDao;
import com.invoice_reader.invoice_reader.database.dao.DossierGeneralParamsDao;
import com.invoice_reader.invoice_reader.database.dao.AccountDao;
import com.invoice_reader.invoice_reader.vente.entity.VenteInvoice;
import com.invoice_reader.invoice_reader.vente.repository.VenteInvoiceRepository;
import com.invoice_reader.invoice_reader.utils.FileStorageService;
import com.invoice_reader.invoice_reader.account_tier.service.TierService;
import com.invoice_reader.invoice_reader.achat.service.ExtractionEngine;
import com.invoice_reader.invoice_reader.achat.service.AchatTemplateService;
import com.invoice_reader.invoice_reader.ocr.service.AdvancedOcrService;
import com.invoice_reader.invoice_reader.ocr.service.AmountValidatorService;
import com.invoice_reader.invoice_reader.ocr.service.BusinessValidationService;
import com.invoice_reader.invoice_reader.ocr.service.CommonInvoiceOcrData;
import com.invoice_reader.invoice_reader.ocr.service.CommonInvoiceOcrService;
import com.invoice_reader.invoice_reader.ocr.service.DocumentClassifierService;
import com.invoice_reader.invoice_reader.ocr.service.OlmocrFallbackService;
import com.invoice_reader.invoice_reader.ocr.service.TextCleaningService;
import com.invoice_reader.invoice_reader.achat.service.pattern.FieldPatternService;
import com.invoice_reader.invoice_reader.utils.ExtractionPatterns;
import com.invoice_reader.invoice_reader.utils.AmountToWordsFormatter;
import com.invoice_reader.invoice_reader.utils.InvoiceTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
 * Service de traitement des factures de vente (VENTE)
 *
 * RÈGLE MÉTIER VENTE:
 * - Signature = PREMIER ICE trouvé dans tout le document (lecture de haut en bas)
 *   → Le premier ICE rencontré est toujours le client (destinataire de la facture)
 *   → Le dernier ICE (dans le footer) est l'émetteur/fournisseur — IGNORÉ
 * - Pas de IF ni RC pour la signature
 * - Liaison Tier uniquement par ICE
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VenteInvoiceProcessingService {

    private final AchatTemplateService dynamicTemplateService;
    private final VenteFieldExtractorService salesFieldExtractorService;
    private final VenteAlphaAgentExtractionService salesAlphaAgentExtractionService;
    private final VenteInvoiceRepository salesInvoiceRepository;
    private final TierService tierService;
    private final AccountDao accountDao;
    private final AdvancedOcrService advancedOcrService;
    private final FileStorageService fileStorageService;
    private final FieldPatternService fieldPatternService;
    private final DossierDao dossierDao;
    private final DossierGeneralParamsDao dossierGeneralParamsDao;
    private final TextCleaningService textCleaningService;
    private final CommonInvoiceOcrService commonInvoiceOcrService;
    private final DocumentClassifierService documentClassifierService;
    private final AmountValidatorService amountValidatorService;
    private final BusinessValidationService businessValidationService;
    private final OlmocrFallbackService olmocrFallbackService;
    @Value("${olmocr.strategy:fallback}")
    private String olmocrStrategy;
    @Value("${olmocr.primary-min-length:120}")
    private int olmocrPrimaryMinLength;

    @Transactional
    public VenteInvoice processInvoice(MultipartFile file, Long dossierId) throws IOException {
        return processInvoice(file, dossierId, ExtractionEngine.DEFAULT);
    }

    @Transactional
    public VenteInvoice processInvoice(MultipartFile file, Long dossierId, ExtractionEngine engine) throws IOException {
        log.info("=== DÉBUT TRAITEMENT FACTURE VENTE ===");
        log.info("Fichier: {}", file.getOriginalFilename());

        // ÉTAPE 1: STOCKAGE FICHIER
        String filePath = fileStorageService.store(file);
        Path path = Paths.get(filePath);
        log.info("Fichier stocké: {}", filePath);

        // ÉTAPE 2: OCR — olmOCR en priorité si activé + PDF, sinon Tesseract
        log.info("Lancement OCR...");
        String ocrText;
        String cleanedOcrText;
        boolean scanned;
        Map<String, Object> ocrMetadata = new HashMap<>();

        boolean tryOlmOcrFirst = "primary".equalsIgnoreCase(olmocrStrategy)
                && olmocrFallbackService.isEnabled()
                && path.toString().toLowerCase().endsWith(".pdf");

        if (tryOlmOcrFirst) {
            log.info("Mode olmOCR primary active pour PDF");
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
                log.info("olmOCR primary reussi: {} caracteres", ocrText.length());
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
            log.warn("OCR echoue: Aucun texte extrait pour {}", file.getOriginalFilename());
        }
        log.info("OCR termine: {} caracteres", ocrText.length());

        // ÉTAPE 3: CRÉATION ENTITY INVOICE
        VenteInvoice invoice = new VenteInvoice();
        invoice.setFilename(file.getOriginalFilename());
        invoice.setOriginalName(file.getOriginalFilename());
        invoice.setFilePath(filePath);
        invoice.setFileSize(file.getSize());
        invoice.setRawOcrText(ocrText);
        invoice.setCleanedOcrText(cleanedOcrText);
        invoice.setScanned(scanned);

        // strategy=fallback : appliquer olmOCR en complement si besoin
        if (!"primary".equalsIgnoreCase(olmocrStrategy)) {
            applyOlmocrFallback(path, invoice, ocrMetadata);
        }
        invoice.setExtractedData(ocrMetadata);

        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setUpdatedAt(LocalDateTime.now());

        // Charger le Dossier via JPA
        if (dossierId == null) {
            throw new IllegalArgumentException("dossierId est requis pour créer une facture de vente");
        }
        Dossier dossier = dossierDao.findById(dossierId)
                .orElseThrow(() -> new IllegalArgumentException("Dossier introuvable: " + dossierId));
        invoice.setDossier(dossier);

        return applyProcessingRules(invoice, engine);
    }

    @Transactional
    public VenteInvoice reprocessExistingInvoice(VenteInvoice invoice) throws IOException {
        log.info("=== RETRAITEMENT FACTURE VENTE EXISTANTE: {} ===", invoice.getId());

        if (invoice.getFilePath() == null) {
            throw new IllegalArgumentException("Chemin de fichier manquant pour la facture " + invoice.getId());
        }

        Path path = Paths.get(invoice.getFilePath());
        if (!Files.exists(path)) {
            throw new IOException("Fichier introuvable sur le disque: " + invoice.getFilePath());
        }

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

        invoice.setRawOcrText(ocrText);
        invoice.setCleanedOcrText(cleanedOcrText);
        invoice.setScanned(scanned);

        if (!"primary".equalsIgnoreCase(olmocrStrategy)) {
            applyOlmocrFallback(path, invoice, ocrMetadata);
        }
        ocrMetadata.put("lastReprocessedAt", LocalDateTime.now());
        invoice.setExtractedData(ocrMetadata);

        invoice.setStatus(InvoiceStatus.PROCESSING);
        invoice.setUpdatedAt(LocalDateTime.now());

        return applyProcessingRules(invoice, ExtractionEngine.DEFAULT);
    }

    private VenteInvoice applyProcessingRules(VenteInvoice invoice, ExtractionEngine engine) {
        String ocrText = invoice.getCleanedOcrText() != null && !invoice.getCleanedOcrText().isBlank()
                ? invoice.getCleanedOcrText()
                : textCleaningService.clean(invoice.getRawOcrText());

        // ÉTAPE 4: DÉTECTION SIGNATURE — PREMIER ICE du document (règle VENTE)
        log.info("Détection signature (premier ICE du document)...");
        TemplateSignature signature = detectFirstIceInDocument(ocrText);
        invoice.setDetectedSignature(signature);

        if (signature != null) {
            log.info("Signature détectée: {} = {}",
                    signature.getSignatureType(), signature.getSignatureValue());
        } else {
            log.warn("Aucun ICE détecté dans le document");
        }

        // ÉTAPE 5: RECHERCHE TEMPLATE
        Optional<AchatTemplate> templateOpt = dynamicTemplateService.detectTemplateBySignature(ocrText);
        AchatTemplate template = null;

        if (templateOpt.isPresent()) {
            template = templateOpt.get();
            invoice.setTemplateId(template.getId());
            invoice.setTemplateName(template.getTemplateName());
            log.info("Template trouvé: {} (ID={})", template.getTemplateName(), template.getId());
        } else {
            log.warn("Aucun template trouvé");
        }

        // ÉTAPE 6: EXTRACTION CHAMPS
        log.info("Extraction des champs...");
        Map<String, Object> fieldsData = new LinkedHashMap<>();
        List<String> autoFilledFields = new ArrayList<>();

        VenteExtractionResult extractionResult;
        if (engine == ExtractionEngine.ALPHA_AGENT) {
            extractionResult = salesAlphaAgentExtractionService.extract(ocrText, template);
            fieldsData.putAll(extractionResult.toSimpleMap());
            log.info("Extraction mode ALPHA_AGENT activé (VENTE)");
        } else if (template != null) {
            extractionResult = salesFieldExtractorService.extractWithTemplate(ocrText, template);
            fieldsData.putAll(extractionResult.toSimpleMap());
        } else {
            extractionResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);
            fieldsData.putAll(extractionResult.toSimpleMap());
        }

        if (template != null && template.getFixedSupplierData() != null) {
            AchatTemplate.FixedSupplierData fixed = template.getFixedSupplierData();
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

        applyMissingFieldsFallback(ocrText, fieldsData, template, extractionResult);

        // Enrichissement métier: on prend l'ICE du partenaire, différent de l'ICE du dossier,
        // puis on complète depuis la table Compte.
        enrichSupplierDataFromAccount(invoice.getDossierId(), ocrText, fieldsData, autoFilledFields);

        // Vérifier la période d'exercice (si configurée)
        boolean withinExercisePeriod = validateInvoiceDateWithinExercise(invoice, fieldsData);

        // RÈGLE ICE DOSSIER: l'ICE du partenaire doit être différent de l'ICE du dossier.
        String dossierIceWarning = evaluateDossierIceRule(invoice.getDossierId(), fieldsData, false);

        // ÉTAPE 7: LIAISON TIER AUTOMATIQUE (par ICE)
        log.info("Liaison Tier automatique...");
        linkInvoiceToTier(invoice, fieldsData, autoFilledFields);

        // ÉTAPE 8: CALCUL MONTANTS
        calculateAndValidateAmounts(fieldsData);

        // ÉTAPE 8B: DÉTECTION AVOIR
        invoice.setIsAvoir(InvoiceTypeDetector.isAvoir(fieldsData, invoice.getRawOcrText()));

        DocumentType documentType = documentClassifierService.classify(ocrText);
        invoice.setDocumentType(documentType);
        if (documentType == DocumentType.AVOIR && !Boolean.TRUE.equals(invoice.getIsAvoir())) {
            invoice.setIsAvoir(true);
        }

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
                businessValidationService.validate(fieldsData, ocrText, true);
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

        // ÉTAPE 9: FINALISATION
        invoice.setFieldsData(fieldsData);
        invoice.setAutoFilledFields(autoFilledFields);
        List<String> lowConfidenceFields = extractionResult.getLowConfidenceFields() != null
                ? new ArrayList<>(extractionResult.getLowConfidenceFields())
                : new ArrayList<>();
        lowConfidenceFields.addAll(validationOutcome.weakFields());
        invoice.setLowConfidenceFields(new ArrayList<>(new LinkedHashSet<>(lowConfidenceFields)));
        invoice.setMissingFields(computeMissingCoreFields(fieldsData));
        invoice.setOverallConfidence(extractionResult.getOverallConfidence());

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
            log.info("Status: TREATED (Manque Tier={}, Template={}, Complet={})",
                    invoice.getTier() != null, invoice.getTemplateId() != null, extractionResult.isComplete());
        }

        VenteInvoice saved = salesInvoiceRepository.save(invoice);

        log.info("=== FIN TRAITEMENT FACTURE VENTE ===");
        log.info("Invoice ID: {}", saved.getId());
        log.info("Status: {}", saved.getStatus());
        log.info("ICE retenu: {}", saved.getIce());
        log.info("Tier: {}", saved.getTierName() != null ? saved.getTierName() : "Aucun");

        return saved;
    }

    private boolean validateInvoiceDateWithinExercise(VenteInvoice invoice, Map<String, Object> fieldsData) {
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

    // ===================== RÈGLE VENTE: PREMIER ICE DU DOCUMENT =====================

    /**
     * RÈGLE VENTE: Scanner le document de haut en bas et retourner le PREMIER ICE trouvé.
     *
     * Logique: Dans une facture de vente marocaine, la structure est toujours:
     *   [ENTÊTE émetteur sans ICE]
     *   [SECTION DESTINATAIRE: ICE du CLIENT]  ← PREMIER ICE = notre cible
     *   [CORPS: articles, montants]
     *   [FOOTER légal émetteur: IF, ICE émetteur, RC]  ← ICE IGNORÉ (c'est le fournisseur)
     */
    private TemplateSignature detectFirstIceInDocument(String text) {
        log.info("=== DÉTECTION PREMIER ICE (VENTE) ===");

        if (text == null || text.isBlank()) {
            log.warn("Texte OCR vide, aucun ICE détecté");
            return null;
        }

        String firstIce = extractFirstIceFromFullText(text);

        if (firstIce != null && firstIce.matches("\\d{15}")) {
            log.info("Premier ICE retenu (CLIENT/DESTINATAIRE): {}", firstIce);
            return new TemplateSignature(SignatureType.ICE, firstIce);
        }

        log.warn("Aucun ICE valide (15 chiffres) trouvé dans le document");
        return null;
    }

    /**
     * Parcourt le texte entier de haut en bas et retourne le PREMIER ICE valide trouvé (15 chiffres).
     * On itère sur tous les patterns ICE mais on s'arrête dès le premier match valide.
     */
    private String extractFirstIceFromFullText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        log.debug("Recherche du premier ICE dans {} caractères", text.length());

        // On collecte TOUS les matches avec leur position dans le texte
        // puis on retourne celui qui apparaît en premier (position la plus basse)
        int firstPosition = Integer.MAX_VALUE;
        String firstIce = null;

        for (String patternStr : ExtractionPatterns.ICE_PATTERNS) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                String ice = ExtractionPatterns.cleanNumber(matcher.group(1));
                if (ice.length() == 15 && ice.matches("\\d{15}")) {
                    int position = matcher.start();
                    if (position < firstPosition) {
                        firstPosition = position;
                        firstIce = ice;
                        log.debug("ICE candidat à position {}: {}", position, ice);
                    }
                }
            }
        }

        if (firstIce != null) {
            log.info("PREMIER ICE dans le document (position {}): {}", firstPosition, firstIce);
        } else {
            log.debug("Aucun ICE trouvé dans le document");
        }

        return firstIce;
    }

    // ===================== LIAISON TIER (ICE uniquement — règle VENTE) =====================

    private void linkInvoiceToTier(VenteInvoice invoice, Map<String, Object> fieldsData,
            List<String> autoFilledFields) {
        log.info("=== RECHERCHE TIER PAR ICE (VENTE) ===");

        String extractedIce = getStringValue(fieldsData, "ice");
        Long dossierId = invoice.getDossier() != null ? invoice.getDossier().getId() : invoice.getDossierId();

        Tier tier = null;

        if (dossierId != null && extractedIce != null && !extractedIce.isBlank()) {
            Optional<TierDto> tierDto = tierService.getTierByIce(extractedIce, dossierId);
            if (tierDto.isPresent()) {
                tier = convertDtoToEntity(tierDto.get());
                log.info("Tier trouvé par ICE: {} (ICE: {})", tier.getLibelle(), extractedIce);
            } else {
                log.warn("Aucun Tier trouvé pour ICE: {} dans dossier {}", extractedIce, dossierId);
            }
        } else {
            log.warn("ICE absent ou dossierId null — liaison Tier impossible");
        }

        if (tier != null) {
            invoice.setTier(tier);
            invoice.setTierId(tier.getId());
            invoice.setTierName(tier.getLibelle());

            fieldsData.put("supplier", tier.getLibelle());
            autoFilledFields.add("supplier");
            log.info("Supplier remplacé par Tier.libelle: {}", tier.getLibelle());

            if (tier.getActivity() != null && !tier.getActivity().isBlank()) {
                fieldsData.put("activity", tier.getActivity());
                autoFilledFields.add("activity");
            }

            if (tier.hasAccountingConfiguration()) {
                fieldsData.put("tierNumber", tier.getTierNumber());
                autoFilledFields.add("tierNumber");

                if (tier.getAuxiliaireMode() != null && tier.getAuxiliaireMode()) {
                    fieldsData.put("collectifAccount", tier.getCollectifAccount());
                    autoFilledFields.add("collectifAccount");
                }

                fieldsData.put("chargeAccount", tier.getEffectiveChargeAccount());
                fieldsData.put("tvaAccount", tier.getEffectiveTvaAccount());
                fieldsData.put("tvaRate", tier.getEffectiveTvaRate());
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("tvaRateUsed", tier.getEffectiveTvaRate());

                autoFilledFields.add("chargeAccount");
                autoFilledFields.add("tvaAccount");
                autoFilledFields.add("tvaRate");

                log.info("Comptes comptables auto-remplis: tierNumber={}, charge={}, tva={}",
                        tier.getTierNumber(), tier.getEffectiveChargeAccount(), tier.getEffectiveTvaAccount());
            }
        }
    }

    // ===================== UTILS =====================

    private Double extractDouble(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString().replaceAll("[^0-9,.]", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double round2(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
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
        for (String token : text.split("\\|")) {
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
                .defaultChargeAccount2(dto.getDefaultChargeAccount2())
                .tvaAccount(dto.getTvaAccount())
                .tvaAccount2(dto.getTvaAccount2())
                .defaultTvaRate(dto.getDefaultTvaRate())
                .defaultTvaRate2(dto.getDefaultTvaRate2())
                .active(dto.getActive())
                .build();
    }

    private void applyMissingFieldsFallback(
            String ocrText,
            Map<String, Object> fieldsData,
            AchatTemplate template,
            VenteExtractionResult extractionResult
    ) {
        if (ocrText == null || ocrText.isBlank() || fieldsData == null) return;

        Set<String> candidates = new LinkedHashSet<>();

        if (extractionResult != null && extractionResult.getMissingFields() != null) {
            candidates.addAll(extractionResult.getMissingFields());
        }

        if (template != null && template.getFieldDefinitions() != null) {
            template.getFieldDefinitions().stream()
                    .map(AchatTemplate.AchatFieldDefinitionJson::getFieldName)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
        }

        candidates.addAll(List.of(
                "invoiceNumber", "invoiceDate", "amountHT", "tva", "amountTTC",
                "ice", "ifNumber", "rcNumber", "supplier"
        ));

        Map<String, Object> heuristicFallback = Collections.emptyMap();
        try {
            VenteExtractionResult heuristicResult = salesFieldExtractorService.extractWithoutTemplate(ocrText);
            if (heuristicResult != null) {
                heuristicFallback = heuristicResult.toSimpleMap();
            }
        } catch (Exception e) {
            log.warn("Fallback heuristique indisponible: {}", e.getMessage());
        }

        for (String fieldName : candidates) {
            if (fieldName == null || fieldName.isBlank()) continue;

            Object existing = fieldsData.get(fieldName);
            if (existing != null && !existing.toString().isBlank()) continue;

            Optional<String> dbMatch = fieldPatternService.extractFirstMatch(fieldName, ocrText);
            if (dbMatch.isPresent()) {
                fieldsData.put(fieldName, dbMatch.get());
                log.info("Fallback field_patterns [{}] = {}", fieldName, dbMatch.get());
                continue;
            }

            Object heuristicValue = heuristicFallback.get(fieldName);
            if (heuristicValue != null && !heuristicValue.toString().isBlank()) {
                fieldsData.put(fieldName, heuristicValue);
                log.info("Fallback heuristique [{}] = {}", fieldName, heuristicValue);
            }
        }
    }

    private void calculateAndValidateAmounts(Map<String, Object> fieldsData) {
        log.info("=== CALCUL ET VALIDATION MONTANTS ===");
        Set<String> computedAmountFields = new LinkedHashSet<>();

        List<Double> multiHt = parseMultiAmounts(fieldsData.get("htValues"));
        List<Double> multiTva = parseMultiAmounts(fieldsData.get("tvaValues"));
        if (multiHt.size() >= 2) {
            double htSum = round2(multiHt.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("amountHT", htSum);
            fieldsData.put("amountHTSource", "MULTI_LINE_SUM");
            computedAmountFields.add("amountHT");
        }
        if (multiTva.size() >= 2) {
            double tvaSum = round2(multiTva.stream().mapToDouble(Double::doubleValue).sum());
            fieldsData.put("tva", tvaSum);
            fieldsData.put("tvaSource", "MULTI_LINE_SUM");
            computedAmountFields.add("tva");
        }

        Double amountHT = extractDouble(fieldsData, "amountHT");
        Double tva = extractDouble(fieldsData, "tva");
        Double amountTTC = extractDouble(fieldsData, "amountTTC");
        Double configuredTvaRate = extractDouble(fieldsData, "tvaRate");

        if (amountHT != null && tva != null) {
            Double calculatedTTC = round2(amountHT + tva);
            if (amountTTC != null) {
                double difference = Math.abs(amountTTC - calculatedTTC);
                if (difference > 0.01) {
                    fieldsData.put("amountTTC", calculatedTTC);
                    fieldsData.put("amountTTCSource", "HT_TVA_SUM");
                    fieldsData.put("ttcDifference", difference);
                    computedAmountFields.add("amountTTC");
                    log.warn("TTC corrige: {} -> {} (ecart {})", amountTTC, calculatedTTC, difference);
                }
            } else {
                fieldsData.put("amountTTC", calculatedTTC);
                fieldsData.put("amountTTCSource", "HT_TVA_SUM");
                computedAmountFields.add("amountTTC");
                log.info("TTC calcule: {}", calculatedTTC);
            }
        }

        amountHT = extractDouble(fieldsData, "amountHT");
        tva = extractDouble(fieldsData, "tva");
        amountTTC = extractDouble(fieldsData, "amountTTC");

        if (amountTTC != null && configuredTvaRate != null && amountHT == null && tva == null) {
            if (Math.abs(configuredTvaRate) < 0.001) {
                fieldsData.put("amountHT", round2(amountTTC));
                fieldsData.put("tva", 0.0);
                fieldsData.put("amountHTSource", "TTC_ZERO_RATE");
                fieldsData.put("tvaSource", "TTC_ZERO_RATE");
                fieldsData.put("tvaRateUsed", configuredTvaRate);
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("amountsCalculationSource", "TTC_AND_TVA_RATE");
                computedAmountFields.add("amountHT");
                computedAmountFields.add("tva");
            } else {
                double calculatedHT = round2(amountTTC / (1.0 + (configuredTvaRate / 100.0)));
                double calculatedTVA = round2(amountTTC - calculatedHT);
                fieldsData.put("amountHT", calculatedHT);
                fieldsData.put("tva", calculatedTVA);
                fieldsData.put("amountHTSource", "TTC_TVA_RATE");
                fieldsData.put("tvaSource", "TTC_TVA_RATE");
                fieldsData.put("tvaRateUsed", configuredTvaRate);
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("amountsCalculationSource", "TTC_AND_TVA_RATE");
                computedAmountFields.add("amountHT");
                computedAmountFields.add("tva");
                log.info("Montants deduits depuis TTC + taux TVA {}%: HT={}, TVA={}",
                        configuredTvaRate, calculatedHT, calculatedTVA);
            }
        }

        amountHT = extractDouble(fieldsData, "amountHT");
        tva = extractDouble(fieldsData, "tva");
        amountTTC = extractDouble(fieldsData, "amountTTC");

        if (amountHT != null && amountTTC != null && tva == null) {
            Double calculatedTVA = round2(amountTTC - amountHT);
            fieldsData.put("tva", calculatedTVA);
            fieldsData.put("tvaSource", "TTC_MINUS_HT");
            computedAmountFields.add("tva");
        }

        if (tva != null && amountTTC != null && amountHT == null) {
            Double calculatedHT = round2(amountTTC - tva);
            fieldsData.put("amountHT", calculatedHT);
            fieldsData.put("amountHTSource", "TTC_MINUS_TVA");
            computedAmountFields.add("amountHT");
        }

        amountHT = extractDouble(fieldsData, "amountHT");
        tva = extractDouble(fieldsData, "tva");

        if (amountHT != null && tva != null && amountHT > 0) {
            double tvaRate = (tva / amountHT) * 100.0;
            fieldsData.put("calculatedTvaRate", Math.round(tvaRate * 100.0) / 100.0);

            if (configuredTvaRate == null) {
                boolean standardRate = false;
                double foundRate = 0.0;
                for (double rate : new double[]{20.0, 14.0, 10.0, 7.0, 0.0}) {
                    if (Math.abs(tvaRate - rate) < 0.5) {
                        foundRate = rate;
                        standardRate = true;
                        break;
                    }
                }

                if (standardRate) {
                    fieldsData.put("tvaRate", foundRate);
                    fieldsData.put("tvaRateSource", "OCR");
                    log.info("Taux TVA standard: {}%", foundRate);
                } else {
                    fieldsData.put("hasValidationWarning", true);
                    fieldsData.put("validationWarningMessage", "Taux TVA non standard: " + Math.round(tvaRate) + "%");
                    log.warn("Taux TVA non standard: {}%", tvaRate);
                }
            } else {
                fieldsData.put("detectedTvaRate", Math.round(tvaRate * 100.0) / 100.0);
                fieldsData.put("tvaRateSource", "ACCOUNT");
                fieldsData.put("tvaRateUsed", configuredTvaRate);
            }
        }

        if (!computedAmountFields.isEmpty()) {
            fieldsData.put("computedAmountFields", new ArrayList<>(new LinkedHashSet<>(computedAmountFields)));
            fieldsData.put("amountsCalculationSource", "OCR_AND_RULES");
        }

        syncAmountTtcEnLettres(fieldsData);
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
        if (amountWords.isBlank()) {
            fieldsData.remove("amountTTCEnLettres");
        } else {
            fieldsData.put("amountTTCEnLettres", amountWords);
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
                "supplier"
        );
        List<String> missing = new ArrayList<>();
        for (String key : coreFields) {
            Object value = fieldsData.get(key);
            if (value == null || value.toString().isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private void applyOlmocrFallback(Path path, VenteInvoice invoice, Map<String, Object> ocrMetadata) {
        // Uniquement strategy=fallback — primary est gere en amont dans processInvoice
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

    private String resolveExtractionMethod(ExtractionEngine engine, AchatTemplate template) {
        if (engine == ExtractionEngine.ALPHA_AGENT) {
            return "ALPHA_AGENT";
        }
        return template != null ? "DYNAMIC_TEMPLATE" : "PATTERNS";
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
        if (matches) {
            fieldsData.put("iceRuleStatus", purchaseFlow
                    ? "PURCHASE_ICE_MATCHES_DOSSIER"
                    : "SALES_ICE_MATCHES_DOSSIER");
            return "ICE facture identique à ICE dossier: vérification manuelle requise.";
        }
        fieldsData.put("iceRuleStatus", "OK");
        return null;
    }

    private void enrichSupplierDataFromAccount(Long dossierId,
                                               String ocrText,
                                               Map<String, Object> fieldsData,
                                               List<String> autoFilledFields) {
        String dossierIce = getDossierIce(dossierId);
        String candidateIce = resolveCounterpartyIce(ocrText, fieldsData, dossierIce);
        if (candidateIce == null) {
            return;
        }

        fieldsData.put("ice", candidateIce);
        markAutoFilled(autoFilledFields, "ice");
        fieldsData.put("detectedIceCandidates", extractIceCandidates(ocrText));
        fieldsData.put("resolvedCounterpartyIce", candidateIce);

        accountDao.findFirstByIceAndActiveTrueOrderByUpdatedAtDesc(candidateIce)
                .ifPresent(account -> applyAccountDefaults(fieldsData, autoFilledFields, account));
    }

    private void applyAccountDefaults(Map<String, Object> fieldsData,
                                      List<String> autoFilledFields,
                                      Account account) {
        Map<String, Object> fixedSupplierData = new LinkedHashMap<>();
        fixedSupplierData.put("ice", normalizeIce(account.getIce()));
        fixedSupplierData.put("ifNumber", trimToNull(account.getIdF()));
        fixedSupplierData.put("rcNumber", trimToNull(account.getRc()));
        fixedSupplierData.put("supplier", trimToNull(account.getLibelle()));
        fixedSupplierData.put("activity", trimToNull(account.getActivite()));
        fixedSupplierData.put("chargeAccount", trimToNull(account.getCharge()));
        fixedSupplierData.put("tvaAccount", trimToNull(account.getTva()));
        fixedSupplierData.put("tvaRate", account.getTvaRate());
        fixedSupplierData.put("taxCode", trimToNull(account.getTaxCode()));
        fixedSupplierData.put("accountCode", trimToNull(account.getCode()));
        fieldsData.put("fixedSupplierData", fixedSupplierData);

        putIfBlank(fieldsData, "supplier", account.getLibelle(), autoFilledFields);
        putIfBlank(fieldsData, "activity", account.getActivite(), autoFilledFields);
        putIfBlank(fieldsData, "ifNumber", account.getIdF(), autoFilledFields);
        putIfBlank(fieldsData, "rcNumber", account.getRc(), autoFilledFields);
        putIfBlank(fieldsData, "chargeAccount", account.getCharge(), autoFilledFields);
        putIfBlank(fieldsData, "tvaAccount", account.getTva(), autoFilledFields);
        putIfBlank(fieldsData, "tvaRate", account.getTvaRate(), autoFilledFields);
        putIfBlank(fieldsData, "taxCode", account.getTaxCode(), autoFilledFields);
    }

    private void putIfBlank(Map<String, Object> fieldsData,
                            String key,
                            Object value,
                            List<String> autoFilledFields) {
        if (value == null) {
            return;
        }
        Object current = fieldsData.get(key);
        if (current == null || String.valueOf(current).isBlank()) {
            fieldsData.put(key, value);
            markAutoFilled(autoFilledFields, key);
        }
    }

    private void markAutoFilled(List<String> autoFilledFields, String key) {
        if (autoFilledFields == null || key == null || key.isBlank()) {
            return;
        }
        if (!autoFilledFields.contains(key)) {
            autoFilledFields.add(key);
        }
    }

    private String resolveCounterpartyIce(String ocrText,
                                          Map<String, Object> fieldsData,
                                          String dossierIce) {
        String normalizedDossierIce = normalizeIce(dossierIce);
        LinkedHashSet<String> candidates = new LinkedHashSet<>(extractIceCandidates(ocrText));
        candidates.addAll(extractIceCandidatesFromValue(fieldsData != null ? fieldsData.get("ice") : null));

        if (candidates.isEmpty()) {
            return null;
        }

        List<String> preferred = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (normalizedDossierIce == null || !normalizedDossierIce.equals(candidate)) {
                preferred.add(candidate);
            }
        }

        if (!preferred.isEmpty()) {
            return preferred.get(0);
        }

        if (normalizedDossierIce != null && candidates.contains(normalizedDossierIce)) {
            return null;
        }

        return candidates.iterator().next();
    }

    private List<String> extractIceCandidates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> explicitMatches = new LinkedHashSet<>();
        LinkedHashSet<String> fallbackMatches = new LinkedHashSet<>();

        for (int i = 0; i < ExtractionPatterns.ICE_PATTERNS.length; i++) {
            Pattern pattern = Pattern.compile(ExtractionPatterns.ICE_PATTERNS[i], Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String candidate = normalizeIce(matcher.group(1));
                if (candidate == null) {
                    continue;
                }
                if (i == ExtractionPatterns.ICE_PATTERNS.length - 1) {
                    fallbackMatches.add(candidate);
                } else {
                    explicitMatches.add(candidate);
                }
            }
        }

        if (!explicitMatches.isEmpty()) {
            return new ArrayList<>(explicitMatches);
        }
        return new ArrayList<>(fallbackMatches);
    }

    private List<String> extractIceCandidatesFromValue(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        if (rawValue instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                String normalized = normalizeIce(item != null ? String.valueOf(item) : null);
                if (normalized != null) {
                    values.add(normalized);
                }
            }
            return values;
        }
        if (rawValue.getClass().isArray()) {
            List<String> values = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(rawValue);
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(rawValue, i);
                String normalized = normalizeIce(item != null ? String.valueOf(item) : null);
                if (normalized != null) {
                    values.add(normalized);
                }
            }
            return values;
        }
        String normalized = normalizeIce(String.valueOf(rawValue));
        return normalized != null ? List.of(normalized) : List.of();
    }

    private String getDossierIce(Long dossierId) {
        if (dossierId == null) {
            return null;
        }
        return dossierGeneralParamsDao.findByDossierId(dossierId)
                .map(params -> normalizeIce(params.getIce()))
                .orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
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
