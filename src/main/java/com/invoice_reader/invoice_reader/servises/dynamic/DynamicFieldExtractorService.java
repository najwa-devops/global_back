package com.invoice_reader.invoice_reader.servises.dynamic;

import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate.DynamicFieldDefinitionJson;
import com.invoice_reader.invoice_reader.utils.ExtractionPatterns;
import com.invoice_reader.invoice_reader.utils.AmountToWordsFormatter;
import com.invoice_reader.invoice_reader.servises.ocr.TextCleaningService;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicFieldExtractorService {

    private final TextCleaningService textCleaningService;

    public DynamicExtractionResult extractWithTemplate(String ocrText, DynamicTemplate template) {
        long start = System.currentTimeMillis();
        String preparedText = prepareOcrText(ocrText);

        Map<String, DynamicExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        for (DynamicFieldDefinitionJson field : template.getFieldDefinitions()) {
            ExtractionAttempt attempt = extractField(preparedText, field);

            if (attempt.value == null || attempt.value.isBlank()) {
                if (Boolean.TRUE.equals(field.getRequired())) {
                    missing.add(field.getFieldName());
                }
                continue;
            }

            boolean lowConf = attempt.confidence < field.getConfidenceThreshold();
            if (lowConf) {
                lowConfidence.add(field.getFieldName());
            }

            extracted.put(field.getFieldName(),
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(attempt.value)
                            .normalizedValue(attempt.value)
                            .confidence(attempt.confidence)
                            .detectionMethod(field.getDetectionMethod())
                            .validated(!lowConf)
                            .validationError(lowConf ? "Confidence faible" : null)
                            .build());
        }

        boolean complete = missing.isEmpty();
        double overallConfidence = extracted.isEmpty()
                ? 0.0
                : extracted.values().stream()
                        .mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0.0)
                        .average()
                        .orElse(0.0);

        return DynamicExtractionResult.builder()
                .templateId(template.getId())
                .templateName(template.getTemplateName())
                .extractedFields(extracted)
                .missingFields(missing)
                .lowConfidenceFields(lowConfidence)
                .overallConfidence(overallConfidence)
                .complete(complete)
                .extractionDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    public DynamicExtractionResult extractWithoutTemplate(String ocrText) {
        long start = System.currentTimeMillis();
        String preparedText = prepareOcrText(ocrText);

        log.info("Extraction sans template - utilisation des patterns par dÃƒÆ’Ã‚Â©faut");

        Map<String, DynamicExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        // ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ CORRECTION: Utiliser les marqueurs de zones si disponibles
        String header = extractZoneText(preparedText, "HEADER");
        String body = extractZoneText(preparedText, "BODY");
        String footer = extractZoneText(preparedText, "FOOTER");

        // Fallback si pas de marqueurs
        if (header == null || header.isBlank()) {
            header = getHeader(preparedText); // Premier 30%
        }
        if (footer == null || footer.isBlank()) {
            footer = getFooter(preparedText); // Dernier 50%
        }
        if (body == null || body.isBlank()) {
            body = preparedText; // Tout le texte comme fallback
        }

        log.debug("Zones extraites - Header: {} chars, Body: {} chars, Footer: {} chars",
                header.length(), body.length(), footer.length());

        // ===================== EXTRACTION HEADER =====================

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: NumÃƒÆ’Ã‚Â©ro de facture (patterns plus flexibles)
        extractAndAdd(extracted, missing, "invoiceNumber", header, Arrays.asList(
                // Pattern - user format: "BL/FACTURE N° ..."
                "(?im)^\\s*BL\\s*/\\s*FACTURE\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern - "Reference / RÃ©fÃ©rence : F-202601-184"
                "(?im)^\\s*(?:R\\p{L}f\\p{L}rence|Reference|Ref\\.?|R[Ã©e]f\\p{L}rence)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 0: "NumÃ©ro de facture : 105/2025"
                "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 0b: "BL/facture n� : XXX"
                "(?im)^\\s*(?:BL\\s*/\\s*)?(?:Facture|FACTURE|Invoice|INVOICE)\\s*N\\s*[^A-Za-z0-9]{0,5}\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 1: "Facture 2026-FA050" (votre cas exact)
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([0-9]{4}-[A-Z]{2}[0-9]+)\\b",

                // Pattern 2: "Facture NÃƒâ€šÃ‚Â° XXX" avec NÃƒâ€šÃ‚Â°
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 2b: "INV-CLI-0005" / "INV-2026-001"
                "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",

                // Pattern 2c: "NÂ° de Facture: FA000132/26"
                "(?im)^\\s*N\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3: "FACTURE: XXX" avec deux-points
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?[:\\s]+([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3b: "Codafiin Facture POS-26-00002"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3c: "( FACTURE NÂ° : 250182 )"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([0-9]{3,})\\b",

                // Pattern 3d: "FACTURE Nâ–‘ : 250182" (OCR NÂ° variantes)
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?N\\s*[^A-Za-z0-9]{0,3}\\s*[:\\-]?\\s*([0-9]{3,})\\b",

                // Pattern 4: Format annÃƒÆ’Ã‚Â©e-lettres-chiffres (2026-FA050, 2024-INV001)
                "\\b([0-9]{4}-[A-Z]{2,}[0-9]+)\\b",

                // Pattern 5: GÃƒÆ’Ã‚Â©nÃƒÆ’Ã‚Â©rique aprÃƒÆ’Ã‚Â¨s "Facture"
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 6: "Correction facture : IN2602-0001"
                "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 7: "N° 12345" (header short form)
                "(?im)^\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));

        if (!extracted.containsKey("invoiceNumber")) {
            ExtractionAttempt invoiceFallback = tryPatterns(preparedText, Arrays.asList(
                    "(?im)^\\s*BL\\s*/\\s*FACTURE\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*(?:R\\p{L}f\\p{L}rence|Reference|Ref\\.?|R[Ã©e]f\\p{L}rence)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*N\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",
                    "(?im)^\\s*(?:FACTURE|Facture|INVOICE|Invoice)\\s*(?:Avoir\\s+)?(?:N[Â°Âºo]|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*(?:BL\\s*/\\s*)?(?:Facture|FACTURE|Invoice|INVOICE)\\s*N\\s*[^A-Za-z0-9]{0,5}\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));
            if (invoiceFallback.value != null && !invoiceFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceNumber", invoiceFallback.value);
                addExtractedField(extracted, "invoiceNumber", normalized, invoiceFallback.confidence);
                missing.remove("invoiceNumber");
            }
        }
        enforcePreferredInvoiceNumber(extracted, header + "\n" + preparedText);

        
        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Date facture (cherche "Date facturation")
        extractAndAdd(extracted, missing, "invoiceDate", header, Arrays.asList(
                // Pattern 1: "Date facturation : 09/02/2026" (votre cas)
                "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 2: "Date de facturation:"
                "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 3: "Date facture:"
                "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 4: "Date:" suivi d'une date
                "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 5: Date avec sÃƒÂ©parateur obligatoire (ÃƒÂ©vite ICE)
                "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                "(?im)\\ble\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                "(?im)\\bLe\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2,4})",
                "(?im)\\b([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2})\\b"));

        if (!extracted.containsKey("invoiceDate")) {
            ExtractionAttempt dateFallback = tryPatterns(preparedText, Arrays.asList(
                    "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",
                    "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                    "(?im)\\ble\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                    "(?im)\\bLe\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2,4})",
                    "(?im)\\b([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2})\\b"));
            if (dateFallback.value != null && !dateFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceDate", dateFallback.value);
                addExtractedField(extracted, "invoiceDate", normalized, dateFallback.confidence);
                missing.remove("invoiceDate");
            }
        }

        if (!extracted.containsKey("invoiceDate")) {
            String looseDate = extractDateFromLabeledContext(preparedText);
            if (looseDate != null) {
                addExtractedField(extracted, "invoiceDate", looseDate, 0.85);
                missing.remove("invoiceDate");
                log.info("Date extraite en mode tolerant: {}", looseDate);
            }
        }

        if (!extracted.containsKey("invoiceDate")) {
            String headerDate = extractDateFromHeaderHeuristic(header);
            if (headerDate != null) {
                addExtractedField(extracted, "invoiceDate", headerDate, 0.80);
                missing.remove("invoiceDate");
                log.info("Date extraite depuis en-tete (heuristique): {}", headerDate);
            }
        }

        // ===================== EXTRACTION FOURNISSEUR (TEXTE COMPLET)
        // =====================

        String sanitizedFooter = sanitizeTextForSupplierIdentifiers(footer);
        String sanitizedFullText = sanitizeTextForSupplierIdentifiers(preparedText);
        String sanitizedHeader = sanitizeTextForSupplierIdentifiers(header);

        // ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ NOUVEAU: Extraction unifiÃƒÆ’Ã‚Â©e (si les trois sont sur la mÃƒÆ’Ã‚Âªme ligne/bloc)
        extractUnifiedIdentifiers(sanitizedFooter, extracted);

        // ICE du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("ice")) {
            String ice = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.ICE_PATTERNS));

            if (ice == null) {
                ice = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
            }

            if (ice == null) {
                ice = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
            }

            if (ice == null) {
                ice = extractIceLoose(sanitizedFooter);
            }
            if (ice == null) {
                ice = extractIceLoose(sanitizedHeader);
            }
            if (ice == null) {
                ice = extractIceLoose(sanitizedFullText);
            }
            if (ice == null) {
                ice = extractIceByProximity(sanitizedFooter);
            }
            if (ice == null) {
                ice = extractIceByProximity(sanitizedFullText);
            }
            if (ice == null) {
                ice = extractIceByProximity(footer);
            }
            if (ice == null) {
                ice = extractIceByProximity(preparedText);
            }

            if (ice != null) {
                ice = ice.replaceAll("\\s+", "");
                if (ice.matches("\\d{15}")) {
                    addExtractedField(extracted, "ice", ice, 0.95);
                    log.info("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ ICE fournisseur extrait depuis FOOTER: {}", ice);
                } else {
                    log.warn("ICE invalide (longueur != 15): {}", ice);
                    missing.add("ice");
                }
            } else {
                log.warn("ÃƒÂ¢Ã‚ÂÃ…â€™ Aucun ICE trouvÃƒÆ’Ã‚Â© dans FOOTER");
                missing.add("ice");
            }
        }

        // IF du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("ifNumber")) {
            String ifNumber = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.IF_PATTERNS));

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.IF_PATTERNS));
            }

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.IF_PATTERNS));
            }
            if (ifNumber == null) {
                ifNumber = extractIfLoose(sanitizedFooter);
            }
            if (ifNumber == null) {
                ifNumber = extractIfLoose(sanitizedFullText);
            }

            if (ifNumber != null) {
                addExtractedField(extracted, "ifNumber", ifNumber, 0.95);
                log.info("IF fournisseur extrait: {}", ifNumber);
            } else {
                log.warn("Aucun IF trouvÃƒÆ’Ã‚Â© dans le footer");
                missing.add("ifNumber");
            }
        }

        // RC du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("rcNumber")) {
            String rc = extractLastMatchStrict(sanitizedFooter,
                    Arrays.asList(ExtractionPatterns.RC_PATTERNS));

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedHeader,
                        Arrays.asList(ExtractionPatterns.RC_PATTERNS));
            }

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedFullText,
                        Arrays.asList(ExtractionPatterns.RC_PATTERNS));
            }

            if (rc != null) {
                addExtractedField(extracted, "rcNumber", rc, 0.95);
                log.info("RC fournisseur extrait depuis FOOTER: {}", rc);
            } else {
                log.warn("Aucun RC trouvÃƒÆ’Ã‚Â© dans FOOTER");
                missing.add("rcNumber");
            }
        }

        // SUPPLIER - Smart extraction using all zones
        String supplier = extractSupplierSmart(header, footer, preparedText);
        if (supplier != null) {
            addExtractedField(extracted, "supplier", supplier, 0.95);
            log.info("Supplier extrait (smart): {}", supplier);
        } else {
            missing.add("supplier");
        }

        // ===================== EXTRACTION MONTANTS =====================

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Montant HT (gÃƒÆ’Ã‚Â¨re virgule ET point) - Cherche dans BODY ou OCR
        String totalsPriorityText = buildTotalsPriorityText(footer, body, preparedText);
        Map<String, String> totalsByLabel = extractTotalsByLabel(totalsPriorityText);
        List<String> allTvaValues = extractAllTvaValues(totalsPriorityText + "\n" + preparedText);
        List<String> allHtValues = extractAllHtValues(totalsPriorityText + "\n" + preparedText);
        Map<String, Double> ventilationAmounts = extractVentilationTvaAmounts(totalsPriorityText + "\n" + preparedText);
        if (ventilationAmounts.containsKey("amountHT")) {
            upsertAmountField(extracted, "amountHT", ventilationAmounts.get("amountHT"), 0.95, "TVA_VENTILATION_BLOCK");
            missing.remove("amountHT");
            log.info("HT extrait via bloc ventilation TVA: {}", ventilationAmounts.get("amountHT"));
        }
        if (ventilationAmounts.containsKey("tva")) {
            upsertAmountField(extracted, "tva", ventilationAmounts.get("tva"), 0.95, "TVA_VENTILATION_BLOCK");
            missing.remove("tva");
            log.info("TVA extraite via bloc ventilation TVA: {}", ventilationAmounts.get("tva"));
        }
        if (ventilationAmounts.containsKey("amountTTC")) {
            upsertAmountField(extracted, "amountTTC", ventilationAmounts.get("amountTTC"), 0.95, "TVA_VENTILATION_BLOCK");
            missing.remove("amountTTC");
            log.info("TTC extrait via bloc ventilation TVA: {}", ventilationAmounts.get("amountTTC"));
        }
        if (ventilationAmounts.containsKey("tva2")) {
            upsertAmountField(extracted, "tva2", ventilationAmounts.get("tva2"), 0.95, "TVA_VENTILATION_BLOCK");
            log.info("TVA secondaire extraite via bloc ventilation: {}", ventilationAmounts.get("tva2"));
        }
        addAmountFromLabeledTotals(extracted, missing, "amountHT", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "tva", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "amountTTC", totalsByLabel);

        if (!extracted.containsKey("amountHT")) {
            extractAmountWithFallback(extracted, missing, "amountHT", totalsPriorityText, preparedText, Arrays.asList(

                // Pattern 1: Matches "Total HT" or "Total H.T." or "Total Hors Taxes"
                // Examples:
                // "Total HT 448,00"
                // "Total H.T. : 448,00"
                // "Total Hors Taxes - 1 234,56"
                "(?i)Total\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",


                // Pattern 2: Matches "Montant HT" or "Montant Hors Taxes"
                // Example: "Montant HT: 448,00"
                "(?i)Montant\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Montant\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s+([\\d\\s]+[,.]\\d{2})",

                // Pattern 2b: "Montant net: 448,00"
                "(?i)Montant\\s+net\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 2c: "Montant total HT: 448,00"
                "(?i)Montant\\s+total\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 3: Matches "Sous-total HT"
                // Example: "Sous-total HT 1 200,00"
                "(?i)Sous[-\\s]?total\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 4: Matches "Net HT"
                // Example: "Net HT: 980,00"
                "(?i)Net\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 5: Matches "Base HT"
                // Example: "Base HT 500,00"
                "(?i)Base\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 6: Matches "Prix HT"
                // Example: "Prix HT : 250,00"
                "(?i)Prix\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 7: Matches "Valeur HT"
                // Example: "Valeur HT 300,00"
                "(?i)Valeur\\s+(?:H\\.?T\\.?|HT)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 8: Matches cases where "HT" appears directly before the amount
                // Example: "HT 448,00"
                "(?i)(?:HT|H\\.T\\.|TT|MI)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 9: Matches cases where the amount appears on the next line (common in OCR table extraction)
                // Example:
                // Total HT
                // 1 234,56
                "(?i)Total\\s+(?:H\\.?T\\.?|HT)[\\s\\n]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 10: "Sous-total : 35.00 MAD"
                "(?i)Sous[-\\s]?total\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?"
            ));
        }



        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: TVA (gÃƒÆ’Ã‚Â¨re "Total TVA 20% 89,60")
        if (!extracted.containsKey("tva")) {
            extractAmountWithFallback(extracted, missing, "tva", totalsPriorityText, preparedText, Arrays.asList(
                // Pattern 0: "TVA (20%) 1 106,00"
                "(?i)T\\.?V\\.?A\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 1: "Total TVA 20% 89,60" (votre cas exact)
                "Total\\s+T\\.?V\\.?A\\.?\\s+(?:\\d{1,2}%)?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 2: "Total T.V.A. : 89,60"
                "Total\\s*T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 3: "TVA 20%: 89,60"
                "(?i)(?:T\\.?V\\.?A\\.?|IVA)\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 3b: "DONT TVA 20% 89,60"
                "(?i)DONT\\s+T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\-\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 3c: "Montant TVA à12% 89,60"
                "(?i)Montant\\s+T\\.?V\\.?A\\.?\\s*[aà]?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\-\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 4: Dans un tableau
                "Total\\s*T\\.?V\\.?A\\.?[\\s\\n]+(?:\\d{1,2}%)?[\\s\\n]*([\\d\\s]+[,.]\\d{2})",

                // Pattern 5: Tv 20 %
                "(?i)(?:T\\.?V\\.?|IVA)\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 6: "Total taxes : 7.00 MAD"
                "(?i)Total\\s+tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?",

                // Pattern 7: "taxes : 7,00"
                "(?i)^\\s*tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?\\s*$"
            ));
        }

        // AMÃƒÆ’Ã¢â‚¬Â°LIORATION: Montant TTC (gÃƒÆ’Ã‚Â¨re point dÃƒÆ’Ã‚Â©cimal "537.60")
        if (!extracted.containsKey("amountTTC")) {
            extractAmountWithFallback(extracted, missing, "amountTTC", totalsPriorityText, preparedText, Arrays.asList(
                // Pattern 0: "Montant NET TTC (MAD) 6 636,00"
                "(?i)Montant\\s+NET\\s+T\\.?T\\.?C\\.?\\s*(?:\\(\\s*MAD\\s*\\))?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 1: "Total TTC 537.60" (votre cas - point dÃƒÆ’Ã‚Â©cimal)
                "(?i)(?:Total\\s+T\\.?T\\.?C\\.?|IRE|TFC)\\s+([\\d\\s]+[,.]\\d{2})",

                // Pattern 2: "Total T.T.C. : 537.60"
                "(?i)(?:Total\\s*T\\.?T\\.?C\\.?|IRE|TFC)\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 3: "Net ÃƒÆ’Ã‚Â  payer: 537.60"
                "(?iu)Net\\s+[aàâ]\\s+payer\\s+T\\.?T\\.?C\\.?\\s*[:\\-\\|]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Net\\s*[^\\n\\d]{0,30}T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Net\\s*[aÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â ]\\s*payer\\s*T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "Net\\s*[ÃƒÆ’Ã‚Â a]\\s*payer\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                "(?i)Net\\s*[^\\n\\d]{0,12}payer(?:\\s*T\\.?T\\.?C\\.?)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // User labels
                "(?i)Montant\\s+total\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?",
                "(?i)Montant\\s+pay[ée]\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?",
                "(?i)Montant\\s+total\\s+T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+net\\s+a\\s+payer\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 4: "Montant TTC:"
                "Montant\\s*T\\.?T\\.?C\\.?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 5: Dans un tableau
                "Total\\s*T\\.?T\\.?C\\.?[\\s\\n]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 6: "TOTAL : 42.00 MAD"
                "(?i)^\\s*TOTAL\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|â‚¬)?\\s*$",
                "(?i)REGLEMENT\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:ESPECES|CHEQUE|VIREMENT|TRAITE|CB)?\\b",
                "(?i)SOMME\\s+DE\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})"));
        }

        if (!allTvaValues.isEmpty()) {
            String tvaValuesDisplay = String.join(" | ", allTvaValues);
            extracted.put("tvaValues",
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(tvaValuesDisplay)
                            .normalizedValue(tvaValuesDisplay)
                            .confidence(0.97)
                            .detectionMethod("MULTI_TVA_DETECTION")
                            .validated(true)
                            .build());
            log.info("TVA detectees: {}", tvaValuesDisplay);

            Double summedTva = allTvaValues.stream()
                    .map(this::parseAmount)
                    .filter(Objects::nonNull)
                    .reduce(0.0, Double::sum);
            if (allTvaValues.size() >= 2 && summedTva != null && summedTva > 0) {
                upsertAmountField(extracted, "tva", summedTva, 0.95, "MULTI_TVA_SUM");
                missing.remove("tva");
                log.info("TVA cumulée depuis tvaValues: {}", summedTva);
            } else if (!extracted.containsKey("tva")) {
                Double primaryTva = parseAmount(allTvaValues.get(0));
                if (primaryTva != null) {
                    upsertAmountField(extracted, "tva", primaryTva, 0.93, "TVA_PRIMARY_FROM_TVA_VALUES");
                    missing.remove("tva");
                    log.info("TVA principale promue depuis tvaValues: {}", primaryTva);
                }
            }

            if (allTvaValues.size() >= 2) {
                Double secondaryTva = parseAmount(allTvaValues.get(1));
                if (secondaryTva != null) {
                    upsertAmountField(extracted, "tva2", secondaryTva, 0.92, "MULTI_TVA_SECONDARY");
                }
            }
        }

        // Extraction des taux TVA (20%, 14%...) pour enrichissement
        List<Integer> tvaRates = extractTvaRates(totalsPriorityText + "\n" + preparedText);
        if (!tvaRates.isEmpty()) {
            extracted.put("tvaRate", DynamicExtractionResult.ExtractedField.builder()
                    .value(tvaRates.get(0) + "%")
                    .normalizedValue(tvaRates.get(0) + "%")
                    .confidence(0.90)
                    .detectionMethod("TVA_RATE_EXTRACTION")
                    .validated(true)
                    .build());
            if (tvaRates.size() >= 2) {
                extracted.put("tvaRate2", DynamicExtractionResult.ExtractedField.builder()
                        .value(tvaRates.get(1) + "%")
                        .normalizedValue(tvaRates.get(1) + "%")
                        .confidence(0.90)
                        .detectionMethod("TVA_RATE_EXTRACTION")
                        .validated(true)
                        .build());
            }
            log.info("Taux TVA extraits: {}", tvaRates);
        }

        if (!allHtValues.isEmpty()) {
            String htValuesDisplay = String.join(" | ", allHtValues);
            extracted.put("htValues",
                    DynamicExtractionResult.ExtractedField.builder()
                            .value(htValuesDisplay)
                            .normalizedValue(htValuesDisplay)
                            .confidence(0.97)
                            .detectionMethod("MULTI_HT_DETECTION")
                            .validated(true)
                            .build());
            log.info("HT detectes: {}", htValuesDisplay);

            Double summedHt = allHtValues.stream()
                    .map(this::parseAmount)
                    .filter(Objects::nonNull)
                    .reduce(0.0, Double::sum);
            if (allHtValues.size() >= 2 && summedHt != null && summedHt > 0) {
                upsertAmountField(extracted, "amountHT", summedHt, 0.95, "MULTI_HT_SUM");
                missing.remove("amountHT");
                log.info("HT cumule depuis htValues: {}", summedHt);
            }
        }

        // NOUVEAU: Extraction depuis les tableaux de TVA (factures type SOREMED)
        // Format: | TAUX | MONTANT TTC | DONT TAXE | MONTANT HT |
        Double htFromTable = extractHtFromTvaTable(preparedText);
        if (htFromTable != null && (!extracted.containsKey("amountHT") || 
                (extracted.containsKey("amountHT") && htFromTable > parseAmount(extracted.get("amountHT").getValue())))) {
            log.info("Tableau TVA: HT extrait={}, utilisation comme amountHT", htFromTable);
            // On stocke temporairement pour la réconciliation
        }

        Double tvaFromTable = extractTvaFromTvaTable(preparedText);
        if (tvaFromTable != null && (!extracted.containsKey("tva") || 
                (extracted.containsKey("tva") && tvaFromTable > parseAmount(extracted.get("tva").getValue())))) {
            log.info("Tableau TVA: TVA extraite={}, utilisation comme tva", tvaFromTable);
            // On stocke temporairement pour la réconciliation
        }

        try {
            applyScannedInvoiceTotalsHeuristics(extracted, missing, totalsPriorityText + "\n" + preparedText);
        } catch (Exception heuristicError) {
            log.warn("Heuristiques montants scannes ignorees (non bloquant): {}", heuristicError.getMessage());
        }

        // NOUVEAU: Appliquer les valeurs du tableau TVA si extraites
        if (htFromTable != null && (!extracted.containsKey("amountHT") || 
                Math.abs(htFromTable - parseAmount(extracted.get("amountHT").getValue())) > 0.01)) {
            upsertAmountField(extracted, "amountHT", htFromTable, 0.90, "TVA_TABLE_EXTRACTION");
            missing.remove("amountHT");
        }
        if (tvaFromTable != null && (!extracted.containsKey("tva") || 
                Math.abs(tvaFromTable - parseAmount(extracted.get("tva").getValue())) > 0.01)) {
            upsertAmountField(extracted, "tva", tvaFromTable, 0.90, "TVA_TABLE_EXTRACTION");
            missing.remove("tva");
        }

        // Fusion tva2 → tva si tva ne les cumule pas déjà (garde multi-taux)
        if (extracted.containsKey("tva2") && extracted.containsKey("tva")) {
            DynamicExtractionResult.ExtractedField tvaField = extracted.get("tva");
            String tvaMethod = tvaField != null ? tvaField.getDetectionMethod() : null;
            boolean alreadySummed = "MULTI_TVA_SUM".equals(tvaMethod)
                    || "TVA_VENTILATION_BLOCK".equals(tvaMethod);
            if (!alreadySummed) {
                Double tvaVal  = getExtractedAmount(extracted, "tva");
                Double tva2Val = getExtractedAmount(extracted, "tva2");
                if (tvaVal != null && tva2Val != null && tva2Val > 0) {
                    double merged = round2(tvaVal + tva2Val);
                    upsertAmountField(extracted, "tva", merged, 0.93, "MULTI_TVA_MERGED");
                    missing.remove("tva");
                    log.info("Multi-taux: tva {} + tva2 {} = {} (TVA totale)", tvaVal, tva2Val, merged);
                }
            }
        }

        reconcileInvoiceAmounts(extracted, missing, lowConfidence, preparedText);

        // Extraction montant TTC en lettres
        try {
            Object amountTtcValue = null;
            DynamicExtractionResult.ExtractedField amountTtcField = extracted.get("amountTTC");
            if (amountTtcField != null) {
                amountTtcValue = amountTtcField.getNormalizedValue() != null
                        ? amountTtcField.getNormalizedValue()
                        : amountTtcField.getValue();
            }
            String amountInLetters = AmountToWordsFormatter.formatTtcInWords(amountTtcValue);
            if (!amountInLetters.isBlank()) {
                extracted.put("amountTTCEnLettres",
                        DynamicExtractionResult.ExtractedField.builder()
                                .value(amountInLetters)
                                .normalizedValue(amountInLetters)
                                .confidence(0.98)
                                .detectionMethod("NUMERIC_TTC_TO_WORDS")
                                .validated(true)
                                .build());
                log.info("Montant TTC en lettres recalculé depuis le TTC numérique: {}", amountInLetters);
            } else if (!extracted.containsKey("amountTTCEnLettres")) {
                String fallbackAmountInLetters = extractAmountTtcEnLettres(preparedText);
                if (fallbackAmountInLetters != null && !fallbackAmountInLetters.isBlank()) {
                    extracted.put("amountTTCEnLettres",
                            DynamicExtractionResult.ExtractedField.builder()
                                    .value(fallbackAmountInLetters)
                                    .normalizedValue(fallbackAmountInLetters)
                                    .confidence(0.88)
                                    .detectionMethod("ARRETE_PHRASE")
                                    .validated(true)
                                    .build());
                    log.info("Montant TTC en lettres extrait: {}", fallbackAmountInLetters);
                }
            }
        } catch (Exception e) {
            log.debug("Extraction amountTTCEnLettres ignoree: {}", e.getMessage());
        }

        // Calculer les mÃƒÆ’Ã‚Â©triques
        boolean complete = missing.isEmpty() || missing.size() <= 2;
        double overallConfidence = extracted.isEmpty()
                ? 0.0
                : extracted.values().stream()
                        .mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0.0)
                        .average()
                        .orElse(0.0);

        log.info("Extraction terminÃƒÆ’Ã‚Â©e: {} champs extraits, {} manquants, confiance {}%",
                extracted.size(), missing.size(), Math.round(overallConfidence * 100));

        return DynamicExtractionResult.builder()
                .templateId(null)
                .templateName("DEFAULT")
                .extractedFields(extracted)
                .missingFields(missing)
                .lowConfidenceFields(lowConfidence)
                .overallConfidence(overallConfidence)
                .complete(complete)
                .extractionDurationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ===================== MeTHODES ZONES =====================
    private String getHeader(String text) {
        if (text == null || text.isEmpty())
            return "";
        int headerEnd = (int) (text.length() * 0.30);
        return text.substring(0, headerEnd);
    }

    private String getFooter(String text) {
        if (text == null || text.isEmpty())
            return "";
        int footerStart = (int) (text.length() * 0.50);
        return text.substring(footerStart);
    }

    // ===================== MeTHODES EXTRACTION =====================
    private void extractAndAdd(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            String text,
            List<String> patterns) {
        ExtractionAttempt attempt = tryPatterns(text, patterns);

        if (attempt.value != null && !attempt.value.isBlank()) {
            String normalizedValue = normalizeValue(fieldName, attempt.value);
            if (normalizedValue == null || normalizedValue.isBlank()) {
                addMissingField(missing, fieldName);
                return;
            }
            addExtractedField(extracted, fieldName, normalizedValue, attempt.confidence);
        } else {
            addMissingField(missing, fieldName);
        }
    }

    private void extractAmountWithFallback(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            String preferredText,
            String fallbackText,
            List<String> patterns) {
        ExtractionAttempt fromPreferred = tryPatternsBestAmount(preferredText, patterns, fieldName);
        ExtractionAttempt selected = fromPreferred;

        if (selected.value == null || selected.value.isBlank()) {
            selected = tryPatternsBestAmount(fallbackText, patterns, fieldName);
        }

        if (selected.value != null && !selected.value.isBlank()) {
            String normalizedValue = normalizeValue(fieldName, selected.value);
            if (parseAmount(normalizedValue) != null) {
                addExtractedField(extracted, fieldName, normalizedValue, selected.confidence);
                return;
            }
        }

        addMissingField(missing, fieldName);
    }

    private void addExtractedField(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName,
            String value,
            double confidence) {
        extracted.put(fieldName,
                DynamicExtractionResult.ExtractedField.builder()
                        .value(value)
                        .normalizedValue(value)
                        .confidence(confidence)
                        .detectionMethod("DEFAULT_PATTERN")
                        .validated(confidence >= 0.7)
                        .validationError(confidence < 0.7 ? "Confidence faible" : null)
                        .build());
    }

    private String extractLastMatch(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String lastMatch = null;

        for (String patternStr : patterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);

                while (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();

                    if (value != null && !value.isBlank()) {
                        String normalized = value.replaceAll("\\s+", "");

                        if (!normalized.isEmpty()) {
                            lastMatch = normalized;
                            log.debug("Match trouvÃƒÆ’Ã‚Â© avec pattern '{}': {}", patternStr, lastMatch);
                        }
                    }
                }

                if (lastMatch != null) {
                    log.debug("Dernier match retenu: {} (pattern: {})", lastMatch, patternStr);
                    return lastMatch;
                }

            } catch (Exception e) {
                log.warn("Erreur avec le pattern '{}': {}", patternStr, e.getMessage());
            }
        }

        return null;
    }

    private String extractSupplierFromFooter(String footer) {
        Pattern siegePattern = Pattern.compile(
                "Si[eÃƒÆ’Ã‚Â¨]ge\\s*social\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]{2,50}?)\\s*[-ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“]",
                Pattern.CASE_INSENSITIVE);
        Matcher siegeMatcher = siegePattern.matcher(footer);
        if (siegeMatcher.find()) {
            String supplier = siegeMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via 'SiÃƒÆ’Ã‚Â¨ge social': {}", supplier);
            return supplier;
        }

        Pattern proprietairePattern = Pattern.compile(
                "(?:Nom\\s*du\\s*propriÃƒÆ’Ã‚Â©taire|Titulaire)\\s*(?:du\\s*compte)?\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]+(?:SARL|SAS|SA|S\\.A\\.R\\.L))",
                Pattern.CASE_INSENSITIVE);
        Matcher proprietaireMatcher = proprietairePattern.matcher(footer);
        if (proprietaireMatcher.find()) {
            String supplier = proprietaireMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via 'Nom du propriÃƒÆ’Ã‚Â©taire': {}", supplier);
            return supplier;
        }

        Pattern saPattern = Pattern.compile(
                "([A-Z][A-Z0-9\\s&.,''()-]{2,40}\\s+(?:SARL|SAS|SA|S\\.A\\.R\\.L|S\\.A))\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher saMatcher = saPattern.matcher(footer);
        String lastSaMatch = null;
        while (saMatcher.find()) {
            lastSaMatch = saMatcher.group(1).trim();
        }
        if (lastSaMatch != null) {
            log.debug("Supplier trouvÃƒÆ’Ã‚Â© via SARL/SA pattern: {}", lastSaMatch);
            return lastSaMatch;
        }

        return null;
    }

    private ExtractionAttempt tryPatterns(String text, List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            String patternStr = patterns.get(i);
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    double confidence = 0.9 - (i * 0.1);

                    log.debug("Pattern match trouvÃƒÆ’Ã‚Â©: '{}' ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ valeur: '{}'",
                            patternStr.substring(0, Math.min(50, patternStr.length())), value);

                    return new ExtractionAttempt(value.trim(), Math.max(confidence, 0.6));
                }
            } catch (Exception e) {
                log.warn("Pattern invalide: {}", patternStr);
            }
        }

        log.debug("Aucun pattern ne correspond");
        return new ExtractionAttempt(null, 0.0);
    }

    private ExtractionAttempt tryPatternsBestAmount(String text, List<String> patterns, String fieldName) {
        if (text == null || text.isBlank()) {
            return new ExtractionAttempt(null, 0.0);
        }

        String sanitizedText = sanitizeTextForAmountExtraction(text);
        AmountCandidate best = null;

        for (int i = 0; i < patterns.size(); i++) {
            String patternStr = patterns.get(i);
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(sanitizedText);
                while (matcher.find()) {
                    String rawValue = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    if (rawValue == null || rawValue.isBlank()) {
                        continue;
                    }

                    String normalizedValue = normalizeValue(fieldName, rawValue);
                    Double numericValue = parseAmount(normalizedValue);
                    if (numericValue == null || numericValue < 0) {
                        continue;
                    }

                    double score = scoreAmountCandidate(sanitizedText, matcher.start(), matcher.end(), i, fieldName);
                    AmountCandidate candidate = new AmountCandidate(normalizedValue, numericValue, score);
                    if (best == null || candidate.score > best.score) {
                        best = candidate;
                    }
                }
            } catch (Exception e) {
                log.warn("Pattern invalide pour montant: {}", patternStr);
            }
        }

        if (best == null) {
            return new ExtractionAttempt(null, 0.0);
        }

        double confidence = Math.max(0.65, Math.min(0.99, best.score));
        return new ExtractionAttempt(best.normalizedValue, confidence);
    }

    private String sanitizeTextForAmountExtraction(String text) {
        if (text == null) {
            return "";
        }
        String sanitized = text
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ');

        sanitized = sanitized.replaceAll("(?<=\\d)[Oo](?=[\\d,\\.])", "0");
        sanitized = sanitized.replaceAll("(?<=[\\d,\\.])[Oo](?=\\d)", "0");
        sanitized = sanitized.replaceAll("(?<=\\d)[Il](?=\\d)", "1");
        sanitized = sanitized.replaceAll("(?<=\\d)S(?=\\d)", "5");

        return sanitized;
    }

    private double scoreAmountCandidate(String text, int start, int end, int patternIndex, String fieldName) {
        int contextStart = Math.max(0, start - 60);
        int contextEnd = Math.min(text.length(), end + 60);
        String context = normalizeTextForMatching(text.substring(contextStart, contextEnd));

        double score = 0.85 - (patternIndex * 0.03);

        if (context.contains("total")) {
            score += 0.10;
        }
        if (context.contains("montant")) {
            score += 0.05;
        }
        if (context.contains("net a payer") || context.contains("net payer")) {
            score += 0.10;
        }

        if ("amountHT".equals(fieldName)
                && (context.contains("ht") || context.contains("hors taxe")
                        || context.matches(".*\\b(?:tt|mi)\\b.*"))) {
            score += 0.08;
        }
        if ("tva".equals(fieldName) && (context.contains("tva") || context.matches(".*\\biva\\b.*"))) {
            score += 0.10;
        }
        if ("amountTTC".equals(fieldName)
                && (context.contains("ttc") || context.matches(".*\\b(?:ire|tfc)\\b.*"))) {
            score += 0.10;
        }
        if ("amountTTC".equals(fieldName) && (context.contains("net a payer") || context.contains("net payer"))) {
            score += 0.18;
        }
        if ("amountTTC".equals(fieldName) && !context.contains("ttc")
                && !context.matches(".*\\b(?:ire|tfc)\\b.*")
                && !context.contains("net a payer")
                && !context.contains("net payer")) {
            score -= 0.25;
        }
        if ("amountTTC".equals(fieldName) && !context.contains("ttc")
                && !context.matches(".*\\b(?:ire|tfc)\\b.*")
                && (context.contains("ht") || context.contains("tva"))) {
            score -= 0.12;
        }

        if (context.contains("prix unitaire") || context.contains("qte") || context.contains("quantite")
                || context.contains("remise")) {
            score -= 0.20;
        }

        if (!text.isEmpty()) {
            score += ((double) start / (double) text.length()) * 0.08;
        }

        return score;
    }

    private String buildTotalsPriorityText(String footer, String body, String fullText) {
        StringBuilder sb = new StringBuilder();
        if (footer != null && !footer.isBlank()) {
            sb.append(footer).append('\n');
        }
        if (body != null && !body.isBlank()) {
            sb.append(body).append('\n');
        }
        if (sb.length() == 0 && fullText != null) {
            sb.append(fullText);
        }
        return sb.toString();
    }

    private void addAmountFromLabeledTotals(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String fieldName,
            Map<String, String> totalsByLabel) {
        if (extracted.containsKey(fieldName)) {
            return;
        }
        String value = totalsByLabel.get(fieldName);
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalizeValue(fieldName, value);
        if (parseAmount(normalized) == null) {
            return;
        }
        addExtractedField(extracted, fieldName, normalized, 0.98);
        missing.remove(fieldName);
        log.info("Montant extrait via bloc totals [{}] = {}", fieldName, normalized);
    }

    private Map<String, String> extractTotalsByLabel(String text) {
        Map<String, String> found = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return found;
        }

        String sanitized = sanitizeTextForAmountExtraction(text);
        String[] lines = sanitized.split("\\R");
        Deque<String> pendingLabels = new ArrayDeque<>();
        String previousLine = null;

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                pendingLabels.clear();
                previousLine = null;
                continue;
            }

            String field = detectTotalField(trimmed);
            String inlineAmount = extractAmountToken(trimmed);

            if (field != null) {
                if (inlineAmount != null) {
                    found.putIfAbsent(field, inlineAmount);
                } else {
                    String amountFromPreviousLine = extractAmountToken(previousLine);
                    if (amountFromPreviousLine != null
                            && shouldUsePreviousAmountForLabel(field, previousLine, trimmed)) {
                        found.putIfAbsent(field, amountFromPreviousLine);
                    } else {
                        pendingLabels.add(field);
                    }
                }
                previousLine = trimmed;
                continue;
            }

            if (!pendingLabels.isEmpty()) {
                String nextAmount = extractAmountToken(trimmed);
                if (nextAmount != null) {
                    String pendingField = pendingLabels.peek();
                    if (pendingField != null && shouldAssignAmountToPendingField(pendingField, trimmed)) {
                        pendingLabels.poll();
                        found.putIfAbsent(pendingField, nextAmount);
                    } else if (isLikelyTvaAmountLine(trimmed) && !found.containsKey("tva")) {
                        found.put("tva", nextAmount);
                    }
                }
            }

            previousLine = trimmed;
        }

        if (!found.containsKey("tva")) {
            String tvaAroundDontLabel = extractTvaAmountAroundDontLabel(sanitized);
            if (tvaAroundDontLabel != null) {
                found.put("tva", tvaAroundDontLabel);
            }
        }

        return found;
    }

    private String extractTvaAmountAroundDontLabel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String amountPattern = "(\\d[\\d\\s]*(?:[,.:]\\d{3})*[,.:]\\d{2})";
        String[] patterns = {
                "(?is)dont\\s+t\\.?v\\.?a\\.?[^\\d\\n]{0,20}(?:\\d{1,2}\\s*%\\s*)?" + amountPattern,
                "(?is)dont\\s+t\\.?v\\.?a\\.?\\s*\\R\\s*(?:\\d{1,2}\\s*%\\s*)?" + amountPattern,
                "(?is)(?:\\d{1,2}\\s*%\\s*)?" + amountPattern + "\\s*\\R\\s*dont\\s+t\\.?v\\.?a\\.?"
        };

        for (String patternStr : patterns) {
            Matcher matcher = Pattern.compile(patternStr).matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return null;
    }

    private boolean shouldUsePreviousAmountForLabel(String fieldName, String previousLine, String currentLabelLine) {
        if (previousLine == null || previousLine.isBlank()) {
            return false;
        }

        String normalizedPrevious = normalizeTextForMatching(previousLine);
        boolean previousLooksStandaloneAmount = previousLine.trim().matches("^[\\d\\s,.:]+(?:\\s*(?:MAD|DH|DHS|EUR|€))?$");
        boolean previousLooksTva = isLikelyTvaAmountLine(previousLine);
        boolean previousLooksNet = normalizedPrevious.contains("net a payer")
                || normalizedPrevious.contains("net payer")
                || normalizedPrevious.contains("total net a payer");

        if ("amountTTC".equals(fieldName)) {
            return (previousLooksStandaloneAmount || previousLooksNet) && !previousLooksTva;
        }
        if ("tva".equals(fieldName)) {
            return previousLooksTva;
        }
        if ("amountHT".equals(fieldName)) {
            return previousLooksStandaloneAmount && !previousLooksTva && !previousLooksNet;
        }
        return false;
    }

    private boolean shouldAssignAmountToPendingField(String fieldName, String candidateLine) {
        boolean candidateLooksTva = isLikelyTvaAmountLine(candidateLine);
        String normalizedCandidate = normalizeTextForMatching(candidateLine);
        boolean candidateLooksNet = normalizedCandidate.contains("net a payer")
                || normalizedCandidate.contains("net payer")
                || normalizedCandidate.contains("total net a payer");

        if ("amountTTC".equals(fieldName)) {
            return !candidateLooksTva;
        }
        if ("tva".equals(fieldName)) {
            return !candidateLooksNet;
        }
        return true;
    }

    private boolean isLikelyTvaAmountLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = normalizeTextForMatching(line);
        return normalized.contains("tva")
                || normalized.contains("taxe")
                || normalized.matches(".*\\b\\d{1,2}\\s*%.*");
    }

    private void reconcileInvoiceAmounts(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            List<String> lowConfidence,
            String sourceText) {
        Double amountHT = getExtractedAmount(extracted, "amountHT");
        Double tva = getExtractedAmount(extracted, "tva");
        Double amountTTC = getExtractedAmount(extracted, "amountTTC");

        // NOUVEAU: Detection speciale - Si TTC == HT, c'est que l'OCR a confondu les colonnes
        // Cas typique des factures avec tableau TVA (type SOREMED)
        if (amountHT != null && amountTTC != null && Math.abs(amountTTC - amountHT) <= 0.01) {
            log.warn("ALERT: TTC == HT ({}), probable erreur d'extraction depuis tableau", amountHT);
            
            // Si on a une TVA valide, recalculer le vrai TTC
            if (tva != null && tva > 0) {
                Double vraiTTC = round2(amountHT + tva);
                log.info("Correction: TTC recalcule = HT {} + TVA {} = {}", amountHT, tva, vraiTTC);
                upsertAmountField(extracted, "amountTTC", vraiTTC, 0.92, "TTC_RECALCULE_FROM_HT");
                amountTTC = vraiTTC;
            }
        }

        amountHT = tryCorrectDuplicatedLeadingDigitAmount(extracted, "amountHT", amountHT,
                amountTTC != null && tva != null ? round2(amountTTC - tva) : null, missing, lowConfidence);
        tva = tryCorrectDuplicatedLeadingDigitAmount(extracted, "tva", tva,
                amountTTC != null && amountHT != null ? round2(amountTTC - amountHT) : null, missing, lowConfidence);
        amountTTC = tryCorrectDuplicatedLeadingDigitAmount(extracted, "amountTTC", amountTTC,
                amountHT != null && tva != null ? round2(amountHT + tva) : null, missing, lowConfidence);

        // Reconstitution des montants manquants via la relation comptable:
        // HT + TVA = TTC
        if (amountHT == null && amountTTC != null && tva != null && amountTTC >= tva) {
            double reconstructedHt = round2(amountTTC - tva);
            if (reconstructedHt >= 0) {
                upsertAmountField(extracted, "amountHT", reconstructedHt, 0.94, "AMOUNT_RECONCILIATION");
                missing.remove("amountHT");
                addLowConfidenceField(lowConfidence, "amountHT");
                amountHT = reconstructedHt;
                log.info("HT manquant -> recalcule via TTC - TVA: {}", reconstructedHt);
            }
        }

        if (tva == null && amountTTC != null && amountHT != null && amountTTC >= amountHT) {
            double reconstructedTva = round2(amountTTC - amountHT);
            if (reconstructedTva >= 0) {
                upsertAmountField(extracted, "tva", reconstructedTva, 0.94, "AMOUNT_RECONCILIATION");
                missing.remove("tva");
                addLowConfidenceField(lowConfidence, "tva");
                tva = reconstructedTva;
                log.info("TVA manquante -> recalculee via TTC - HT: {}", reconstructedTva);
            }
        }

        if (amountHT == null || tva == null) {
            if (amountTTC == null && sourceText != null && !sourceText.isBlank()) {
                Double bestTtc = findBestTtcCandidate(sourceText, amountHT, tva);
                if (bestTtc != null) {
                    upsertAmountField(extracted, "amountTTC", bestTtc, 0.95, "TTC_TEXTUAL_RESCAN");
                    missing.remove("amountTTC");
                    amountTTC = bestTtc;
                    log.info("TTC retrouvé via rescannage textuel: {}", bestTtc);
                }
            }
            return;
        }

        double expectedTtc = round2(amountHT + tva);

        if (amountTTC == null) {
            upsertAmountField(extracted, "amountTTC", expectedTtc, 0.96, "AMOUNT_RECONCILIATION");
            missing.remove("amountTTC");
            log.info("TTC manquant -> recalcule via HT + TVA: {}", expectedTtc);
            return;
        }

        double difference = Math.abs(amountTTC - expectedTtc);
        boolean ttcEqualsTva = Math.abs(amountTTC - tva) <= 0.01;
        boolean ttcEqualsHt = Math.abs(amountTTC - amountHT) <= 0.01;
        boolean largeGap = expectedTtc > 0
                && difference > 0.50
                && (difference / expectedTtc) >= 0.10;
        boolean tvaClearlyInvalid = tva > amountTTC && amountTTC >= amountHT;
        boolean ttcLooksPlausible = amountTTC >= amountHT && amountTTC > 0;
        boolean tvaLooksPlausible = tva >= 0 && amountHT > 0 && tva <= amountHT;
        boolean likelyTtcOcrNoise = isLikelyTtcOcrNoise(amountTTC, expectedTtc);

        if (tvaClearlyInvalid) {
            Double fallbackTva = findReasonableTvaCandidate(extracted, amountTTC);
            if (fallbackTva != null) {
                upsertAmountField(extracted, "tva", fallbackTva, 0.95, "MULTI_TVA_FALLBACK");
                missing.remove("tva");
                addLowConfidenceField(lowConfidence, "tva");
                log.warn("TVA incoherente (extrait={}, TTC={}) -> fallback TVA applique: {}",
                        tva, amountTTC, fallbackTva);
            }
            if (ttcLooksPlausible) {
                addLowConfidenceField(lowConfidence, "tva");
                return;
            }
        }

        if (ttcLooksPlausible && largeGap && !ttcEqualsTva && !ttcEqualsHt) {
            if (tvaLooksPlausible && likelyTtcOcrNoise) {
                upsertAmountField(extracted, "amountTTC", expectedTtc, 0.96, "AMOUNT_RECONCILIATION");
                missing.remove("amountTTC");
                addLowConfidenceField(lowConfidence, "amountTTC");
                log.warn("TTC probable bruit OCR (extrait={}, attendu={}) -> correction appliquee",
                        amountTTC, expectedTtc);
                return;
            }
            addLowConfidenceField(lowConfidence, "tva");
            log.warn("Conflit montants detecte mais TTC semble plausible (TTC={}, HT={}, TVA={}) -> TTC conserve",
                    amountTTC, amountHT, tva);
            return;
        }

        if (ttcEqualsTva || ttcEqualsHt || largeGap) {
            upsertAmountField(extracted, "amountTTC", expectedTtc, 0.96, "AMOUNT_RECONCILIATION");
            missing.remove("amountTTC");
            log.warn("TTC incoherent (extrait={}, attendu={}) -> correction appliquee", amountTTC, expectedTtc);
            return;
        }

        if (sourceText != null && !sourceText.isBlank()) {
            Double bestTtc = findBestTtcCandidate(sourceText, amountHT, tva);
            if (bestTtc != null && Math.abs(bestTtc - expectedTtc) <= Math.max(1.0, expectedTtc * 0.10)) {
                upsertAmountField(extracted, "amountTTC", bestTtc, 0.94, "TTC_TEXTUAL_RESCAN");
                missing.remove("amountTTC");
                log.info("TTC revalide via rescannage textuel: {}", bestTtc);
            }
        }
    }

    private void applyScannedInvoiceTotalsHeuristics(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            List<String> missing,
            String text) {
        try {
            if (text == null || text.isBlank()) {
                return;
            }

            String sanitized = sanitizeTextForAmountExtraction(text);

            // Fallback 1: "Arrêté la présente facture à la somme de Six cent cinquante DH"
            if (!extracted.containsKey("amountTTC")) {
                Double ttcFromWords = extractAmountFromArretePhrase(sanitized);
                if (ttcFromWords != null && ttcFromWords > 0) {
                    upsertAmountField(extracted, "amountTTC", ttcFromWords, 0.90, "TEXTUAL_TOTAL_FALLBACK");
                    missing.remove("amountTTC");
                    log.info("TTC extrait depuis montant en lettres: {}", ttcFromWords);
                }
            }

            // Fallback 2: même ligne TVA + TTC
            if (!extracted.containsKey("tva") || !extracted.containsKey("amountTTC")) {
                Pattern tvaTtcSameLine = Pattern.compile(
                        "(?i)dont\\s+t\\.?v\\.?a\\.?[^\\n\\d]{0,20}\\d{1,2}\\s*%?\\s*([\\d\\s.,]+).*?(?:net\\s*a\\s*payer|net\\s*payer|total\\s*net\\s*a\\s*payer|reglement)\\s*[:\\-]?\\s*([\\d\\s.,]+)");
                Matcher pairMatcher = tvaTtcSameLine.matcher(sanitized);
                while (pairMatcher.find()) {
                    String rawTva = pairMatcher.group(1);
                    String rawTtc = pairMatcher.group(2);
                    Double candidateTtc = normalizeScannedAmountCandidate(rawTtc, null);
                    Double candidateTva = normalizeScannedAmountCandidate(rawTva, candidateTtc);
                    if (candidateTtc == null || candidateTva == null || candidateTtc < candidateTva) {
                        continue;
                    }
                    if (!extracted.containsKey("tva")) {
                        upsertAmountField(extracted, "tva", candidateTva, 0.90, "SAME_LINE_TVA_TTC_HEURISTIC");
                        missing.remove("tva");
                        log.info("TVA extraite via ligne TVA+NET: {}", candidateTva);
                    }
                    if (!extracted.containsKey("amountTTC")) {
                        upsertAmountField(extracted, "amountTTC", candidateTtc, 0.92, "SAME_LINE_TVA_TTC_HEURISTIC");
                        missing.remove("amountTTC");
                        log.info("TTC extrait via ligne TVA+NET: {}", candidateTtc);
                    }
                    break;
                }
            }

            // Fallback 3: ligne Total scannée avec 2 montants
            if (!extracted.containsKey("tva") || !extracted.containsKey("amountTTC")) {
                String[] lines = sanitized.split("\\R");
                Pattern amountPattern = Pattern.compile("(\\d[\\d\\s]{0,20}[,.]\\d{2})");
                for (String line : lines) {
                    if (line == null) {
                        continue;
                    }
                    String normalizedLine = normalizeTextForMatching(line);
                    if (!normalizedLine.contains("total")
                            || normalizedLine.contains("ttc")
                            || normalizedLine.contains("tva")
                            || normalizedLine.contains("ht")) {
                        continue;
                    }

                    Matcher m = amountPattern.matcher(line);
                    List<Double> amounts = new ArrayList<>();
                    while (m.find()) {
                        Double val = parseAmount(m.group(1));
                        if (val != null && val >= 0) {
                            amounts.add(val);
                        }
                    }

                    if (amounts.size() < 2) {
                        continue;
                    }

                    Double candidateTva = amounts.get(amounts.size() - 2);
                    Double candidateTtc = amounts.get(amounts.size() - 1);
                    if (candidateTva == null || candidateTtc == null) {
                        continue;
                    }
                    if (candidateTtc >= candidateTva) {
                        if (!extracted.containsKey("tva")) {
                            upsertAmountField(extracted, "tva", candidateTva, 0.88, "TOTAL_LINE_HEURISTIC");
                            missing.remove("tva");
                            log.info("TVA extraite via ligne Total scannee: {}", candidateTva);
                        }
                        if (!extracted.containsKey("amountTTC")) {
                            upsertAmountField(extracted, "amountTTC", candidateTtc, 0.90, "TOTAL_LINE_HEURISTIC");
                            missing.remove("amountTTC");
                            log.info("TTC extrait via ligne Total scannee: {}", candidateTtc);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Heuristiques scan montants ignorées (safe fallback): {}", e.getMessage());
        }
    }

    private Double normalizeScannedAmountCandidate(String raw, Double upperBound) {
        Double parsed = parseAmount(raw);
        if (parsed == null || parsed < 0) {
            return null;
        }

        boolean hasSeparator = raw != null && (raw.contains(",") || raw.contains("."));
        if (!hasSeparator && parsed >= 1000) {
            double shifted = round2(parsed / 100.0);
            if (upperBound != null) {
                if (shifted > 0 && shifted <= upperBound * 1.2) {
                    return shifted;
                }
            } else if (shifted > 0 && shifted <= 1_000_000) {
                return shifted;
            }
        }

        return parsed;
    }

    // -------------------------------------------------------------------------
    // Montant TTC en lettres
    // -------------------------------------------------------------------------

    /**
     * Extrait la représentation textuelle du montant TTC depuis le texte OCR.
     *
     * Gère 3 formes rencontrées sur les factures marocaines :
     *  1. Phrase naturelle   : "Arrêté la présente facture à la somme de Deux mille ..."
     *  2. Phrase concaténée  : "ARRETEELAPRESENTEFACTUREALASOMMEDE\nDeux mille ..."
     *  3. Montant all-caps   : "DEUXMILLEQUATRECENTSOIXANTEDIRHAMS" (sans espaces)
     */
    private String extractAmountTtcEnLettres(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // --- Pattern 1 : phrase "Arrêté … somme de …" (avec ou sans espaces, multi-ligne) ---
        Pattern arretePhrase = Pattern.compile(
                "(?is)arr[êe]t[ée]e?[\\w\\s]*?(?:somme\\s+de\\s*[:\\-]?\\s*|sommede\\s*)" +
                "([a-z\\u00c0-\\u00ff][a-z\\u00c0-\\u00ff\\s\\-]{3,}" +
                "(?:dirham|dh)s?(?:[^\\n]{0,40}(?:centime|ct)s?)?)"
        );
        Matcher m1 = arretePhrase.matcher(text);
        if (m1.find()) {
            return normalizeAmountLettersText(m1.group(1).trim());
        }

        // --- Pattern 2 : ligne standalone avec "dirhams" en français naturel ---
        // ex: "Deux mille quatre cent soixante dirhams"
        Pattern standalonePattern = Pattern.compile(
                "(?im)^\\s*([a-z\\u00c0-\\u00ff][a-z\\u00c0-\\u00ff\\s\\-]{5,}" +
                "(?:dirham|dh)s?(?:\\s+et\\s+[a-z\\s]+(?:centime|ct)s?)?)\\s*$"
        );
        Matcher m2 = standalonePattern.matcher(text.toLowerCase());
        while (m2.find()) {
            String candidate = m2.group(1).trim();
            if (containsFrenchNumberWord(candidate)) {
                // Re-extract from original text to preserve casing
                return normalizeAmountLettersText(candidate);
            }
        }

        // --- Pattern 3 : montant all-caps concaténé sans espaces ---
        // ex: "DEUXMILLEQUATRECENTSOIXANTEDIRHAMS" ou "DEUXMIELEDEUXCENTTRENTESEPTDIRHAMSET:OO.CTS"
        Pattern concatPattern = Pattern.compile(
                "(?i)\\b([A-Z]{4,}DIRHAM[SE]*(?:[A-Z0-9:. ]{0,30}(?:CENTIME|CT[S]?)?)?)\\b"
        );
        Matcher m3 = concatPattern.matcher(text.toUpperCase());
        while (m3.find()) {
            String candidate = m3.group(1);
            if (candidate.length() >= 12) {
                return normalizeAmountLettersText(candidate);
            }
        }

        return null;
    }

    /** Vérifie qu'une chaîne contient au moins un mot-nombre français. */
    private boolean containsFrenchNumberWord(String text) {
        String lower = text.toLowerCase();
        for (String w : new String[]{"deux","trois","quatre","cinq","six","sept","huit","neuf",
                "dix","onze","douze","treize","quatorze","quinze","seize",
                "vingt","trente","quarante","cinquante","soixante","cent","mille","million"}) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalise un montant en lettres (naturel ou concaténé OCR).
     *
     * Exemples :
     *   "DEUXMIELEDEUXCENTTRENTESEPTDIRHAMSET:OO.CTS"
     *     → "Deux mille deux cent trente sept dirhams et 00 cts"
     *   "Deux mille quatre cent soixante dirhams"
     *     → "Deux mille quatre cent soixante dirhams"
     */
    private String normalizeAmountLettersText(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String text = raw.toUpperCase()
                .replace("MIELE", "MILLE")
                .replace("MlLLE", "MILLE")
                .replace("MlLE",  "MILLE")
                .replaceAll(":0+\\.?", " 00 ")
                .replaceAll(":O+\\.?", " 00 ")
                .replace("DIRHAMSET", "DIRHAMS ET")
                .replace("DIRHAMET",  "DIRHAM ET")
                .replaceAll("(?<=[A-Z])(CTS|CT)$", " $1")
                .replaceAll("(?<=[A-Z])[:\\|](?=[A-Z0-9])", " ");

        // Insert spaces before each French number/currency word
        text = text.replaceAll(
                "(?<=[A-Z\\u00C0-\\u00D6\\u00D8-\\u00DE])(?=" +
                "MILLIARD[S]?|MILLION[S]?|MILLE|CENTIME[S]?|DIRHAM[S]?|" +
                "SOIXANTE|CINQUANTE|QUARANTE|TRENTE|VINGT|" +
                "SEIZE|QUINZE|QUATORZE|TREIZE|DOUZE|ONZE|DIX|" +
                "NEUF|HUIT|SEPT|SIX|CINQ|QUATRE|TROIS|DEUX|Z[EÉ]RO|" +
                "CENT[S]?|CTS?|ET(?=[A-Z])|UN[E]?(?=[A-Z]|$)" +
                ")", " ");

        // Lowercase + capitalize first letter
        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ").trim();
        if (!lower.isEmpty()) {
            lower = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
        return lower;
    }

    private String prepareOcrText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String cleaned = textCleaningService != null ? textCleaningService.clean(rawText) : rawText;
        if (cleaned == null || cleaned.isBlank()) {
            return rawText;
        }

        String rawScoreText = normalizeTextForMatching(rawText);
        String cleanedScoreText = normalizeTextForMatching(cleaned);
        int rawScore = scoreExtractionText(rawScoreText);
        int cleanedScore = scoreExtractionText(cleanedScoreText);

        if (cleaned.length() >= rawText.length() * 0.75 || cleanedScore >= rawScore) {
            return cleaned;
        }
        return rawText;
    }

    private int scoreExtractionText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int score = text.length();
        String[] keywords = {
                "facture", "invoice", "total", "tva", "ht", "ttc", "ice", "if", "rc",
                "fournisseur", "client", "date", "montant", "net", "payer", "somme"
        };
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score += 120;
            }
        }
        return score;
    }

    private Double extractAmountFromArretePhrase(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Pattern arretePattern = Pattern.compile(
                "(?is)arr[êe]t[ée]\\s+la\\s+pr[ée]sente\\s+facture\\s+[aà]\\s+la\\s+somme\\s+de\\s+([a-zàâäéèêëîïôöùûüç\\-\\s]+?)\\s*(?:dh|dirham|mad)\\b");
        Matcher matcher = arretePattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String words = matcher.group(1);
        Integer parsed = parseFrenchNumberWords(words);
        return parsed == null ? null : (double) parsed;
    }

    private Integer parseFrenchNumberWords(String words) {
        if (words == null || words.isBlank()) {
            return null;
        }

        String normalized = Normalizer.normalize(words, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }

        Map<String, Integer> units = new HashMap<>();
        units.put("zero", 0);
        units.put("un", 1);
        units.put("une", 1);
        units.put("deux", 2);
        units.put("trois", 3);
        units.put("quatre", 4);
        units.put("cinq", 5);
        units.put("six", 6);
        units.put("sept", 7);
        units.put("huit", 8);
        units.put("neuf", 9);
        units.put("dix", 10);
        units.put("onze", 11);
        units.put("douze", 12);
        units.put("treize", 13);
        units.put("quatorze", 14);
        units.put("quinze", 15);
        units.put("seize", 16);

        Map<String, Integer> tens = new HashMap<>();
        tens.put("vingt", 20);
        tens.put("trente", 30);
        tens.put("quarante", 40);
        tens.put("cinquante", 50);
        tens.put("soixante", 60);

        int total = 0;
        int current = 0;
        for (String token : normalized.split(" ")) {
            if (token.isBlank() || "et".equals(token)) {
                continue;
            }
            if (units.containsKey(token)) {
                current += units.get(token);
                continue;
            }
            if (tens.containsKey(token)) {
                current += tens.get(token);
                continue;
            }
            if ("cent".equals(token) || "cents".equals(token)) {
                if (current == 0) {
                    current = 1;
                }
                current *= 100;
                continue;
            }
            if ("mille".equals(token)) {
                if (current == 0) {
                    current = 1;
                }
                total += current * 1000;
                current = 0;
            }
        }

        int parsed = total + current;
        return parsed > 0 ? parsed : null;
    }

    private Double getExtractedAmount(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName) {
        DynamicExtractionResult.ExtractedField field = extracted.get(fieldName);
        if (field == null) {
            return null;
        }
        String raw = field.getNormalizedValue() != null
                ? String.valueOf(field.getNormalizedValue())
                : field.getValue();
        return parseAmount(raw);
    }

    private void upsertAmountField(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName,
            double value,
            double confidence,
            String detectionMethod) {
        String formatted = formatAmount(value);
        extracted.put(fieldName,
                DynamicExtractionResult.ExtractedField.builder()
                        .value(formatted)
                        .normalizedValue(formatted)
                        .confidence(confidence)
                        .detectionMethod(detectionMethod)
                        .validated(confidence >= 0.7)
                        .validationError(confidence < 0.7 ? "Confidence faible" : null)
                        .build());
    }

    private String detectTotalField(String line) {
        String normalized = normalizeTextForMatching(line)
                .replace(" ", "");

        if (normalized.contains("donttva")) {
            return "tva";
        }
        if (normalized.contains("totalnetapayer")) {
            return "amountTTC";
        }
        if (normalized.contains("montantnetttc")) {
            return "amountTTC";
        }
        if (normalized.matches("^(?:ire|tfc)(?:\\s|\\d|dh|mad|$).*")) {
            return "amountTTC";
        }
        if (normalized.contains("netapayer") && normalized.contains("ttc")) {
            return "amountTTC";
        }
        if (normalized.contains("netpayer") && normalized.contains("ttc")) {
            return "amountTTC";
        }
        if (normalized.contains("netapayer")) {
            return "amountTTC";
        }
        if (normalized.contains("netpayer")) {
            return "amountTTC";
        }
        if (normalized.startsWith("tva(") || normalized.startsWith("tva") || normalized.contains("totaltva")
                || normalized.matches("^iva(?:\\s|\\d|%|dh|mad|$).*")) {
            return "tva";
        }
        if (normalized.matches("^(?:tt|mi)(?:\\s|\\d|dh|mad|$).*")) {
            return "amountHT";
        }

        if (normalized.contains("total")) {
            if (normalized.contains("ttc")) {
                return "amountTTC";
            }
            if (normalized.contains("tva")) {
                return "tva";
            }
            if (normalized.contains("ht") || normalized.contains("horstaxe")) {
                return "amountHT";
            }
        }
        return null;
    }

    private String normalizeTextForMatching(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractAmountToken(String line) {
        Matcher matcher = Pattern.compile("(\\d[\\d\\s]*(?:[,.:]\\d{3})*[,.:]\\d{2})").matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private boolean isLikelyTtcOcrNoise(double extractedTtc, double expectedTtc) {
        if (extractedTtc <= expectedTtc) {
            return false;
        }
        long extractedRounded = Math.round(extractedTtc);
        long expectedRounded = Math.round(expectedTtc);
        long absDiff = Math.abs(extractedRounded - expectedRounded);
        if (absDiff < 1000) {
            return false;
        }
        return extractedRounded % 1000 == expectedRounded % 1000;
    }

    private Double findReasonableTvaCandidate(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            double amountTTC) {
        DynamicExtractionResult.ExtractedField tvaValuesField = extracted.get("tvaValues");
        if (tvaValuesField == null || tvaValuesField.getNormalizedValue() == null) {
            return null;
        }
        String raw = String.valueOf(tvaValuesField.getNormalizedValue());
        if (raw.isBlank()) {
            return null;
        }
        Double best = null;
        for (String token : raw.split("\\|")) {
            Double candidate = parseAmount(token);
            if (candidate == null || candidate < 0 || candidate > amountTTC) {
                continue;
            }
            if (best == null || candidate < best) {
                best = candidate;
            }
        }
        return best;
    }

    private Double findBestTtcCandidate(String text, Double amountHT, Double tva) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String sanitized = sanitizeTextForAmountExtraction(text);
        String[] lines = sanitized.split("\\R");
        Double best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }

            String normalized = normalizeTextForMatching(line);
            boolean labelHint = normalized.contains("net a payer")
                    || normalized.contains("total ttc")
                    || normalized.contains("montant total")
                    || normalized.contains("somme de")
                    || normalized.contains("a payer")
                    || normalized.contains("reglement")
                    || normalized.contains("total");
            if (!labelHint) {
                continue;
            }

            Matcher matcher = Pattern.compile("(\\d[\\d\\s]{0,20}[,.]\\d{2})").matcher(line);
            while (matcher.find()) {
                Double candidate = normalizeScannedAmountCandidate(matcher.group(1), amountHT);
                if (candidate == null || candidate <= 0) {
                    continue;
                }

                double score = candidate;
                if (normalized.contains("net a payer")) {
                    score += 40;
                }
                if (normalized.contains("total ttc")) {
                    score += 35;
                }
                if (normalized.contains("montant total")) {
                    score += 30;
                }
                if (normalized.contains("somme de")) {
                    score += 25;
                }
                if (amountHT != null) {
                    double expected = amountHT + (tva != null ? tva : 0.0);
                    double diff = Math.abs(candidate - expected);
                    score -= Math.min(diff, 500.0) / 8.0;
                }

                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        return best;
    }

    private List<String> extractAllTvaValues(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        String[] patterns = {
                "(?i)T\\.?V\\.?A\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)DONT\\s+T\\.?V\\.?A\\.?\\s*(?:\\d{1,2}\\s*%)?\\s*[:\\-]?\\s*([\\d\\s]+[,.:]\\d{2})",
                // "TVA 20,00 % 333,17" — taux décimal avec espace avant %
                "(?i)T\\.?V\\.?A\\.?\\s+\\d{1,2}[,.]?\\d{0,2}\\s*%\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                // "TVA à 20% : 333,17" — forme française avec accent
                "(?iu)T\\.?V\\.?A\\.?\\s+[aà]\\s+\\d{1,2}\\s*%\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                // "Taxe : 333,17" ou "T.V.A. : 333,17" seul sur la ligne
                "(?im)^\\s*(?:Taxe|T\\.?V\\.?A\\.?)\\s*[:\\-]\\s*([\\d\\s]+[,.]\\d{2})\\s*$"
        };

        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
            while (matcher.find()) {
                String raw = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String normalized = normalizeValue("tva", raw);
                if (parseAmount(normalized) != null) {
                    values.add(normalized);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private List<String> extractAllHtValues(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        String[] patterns = {
                "(?i)(?:Total\\s+)?H\\.?T\\.?\\s*\\(?\\s*\\d{1,2}\\s*%\\s*\\)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)(?:Sous[-\\s]?total|Base|Montant)\\s+H\\.?T\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)H\\.?T\\.?\\s*[:\\-]\\s*([\\d\\s]+[,.]\\d{2})"
        };

        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            while (matcher.find()) {
                String raw = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String normalized = normalizeValue("amountHT", raw);
                if (parseAmount(normalized) != null) {
                    values.add(normalized);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private List<Integer> extractTvaRates(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Integer> rates = new LinkedHashSet<>();
        Matcher m = Pattern.compile(
                "(?i)T\\.?V\\.?A\\.?\\s*[\\(aà]?\\s*(\\d{1,2})(?:[,.]\\d{0,2})?\\s*%",
                Pattern.MULTILINE).matcher(text);
        while (m.find()) {
            try {
                int rate = Integer.parseInt(m.group(1).trim());
                if (rate > 0 && rate <= 100) {
                    rates.add(rate);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(rates);
    }

    /**
     * Extrait HT / TVA / TTC depuis un bloc "Ventilation des TVA" scanné ligne par ligne.
     * Cas typique:
     * Ventilation des TVA
     * TVA
     * Taux TVA
     * HT
     * TVA
     * TTC
     * TVA A 20%
     * 20,00 %
     * 1 665,8333
     * 333,1667
     * 1 999,00
     */
    private Map<String, Double> extractVentilationTvaAmounts(String text) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String sanitized = sanitizeTextForAmountExtraction(text);
        String[] lines = sanitized.split("\\R");
        int startIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String normalized = normalizeTextForMatching(lines[i]);
            if (normalized.contains("ventilation") && normalized.contains("tva")) {
                startIndex = i;
                break;
            }
        }

        if (startIndex < 0) {
            return result;
        }

        List<Double> amounts = new ArrayList<>();
        for (int i = startIndex; i < Math.min(lines.length, startIndex + 48); i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }

            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            String normalized = normalizeTextForMatching(trimmed);
            if (normalized.contains("ventilation") && normalized.contains("tva")) {
                continue;
            }
            if (normalized.contains("taux tva")
                    || normalized.equals("tva")
                    || normalized.equals("ht")
                    || normalized.equals("itc")
                    || normalized.contains("total")) {
                continue;
            }
            if (trimmed.contains("%")) {
                continue;
            }

            String token = extractAmountToken(trimmed);
            if (token == null) {
                continue;
            }

            Double parsed = parseAmount(token);
            if (parsed == null || parsed < 0) {
                continue;
            }

            amounts.add(parsed);
            // Pas de break — on collecte tous les montants du bloc (support N taux)
        }

        if (amounts.size() >= 3) {
            int groups = amounts.size() / 3;
            double totalHt = 0, totalTva = 0, totalTtc = 0;
            for (int g = 0; g < groups; g++) {
                totalHt  += amounts.get(g * 3);
                totalTva += amounts.get(g * 3 + 1);
                totalTtc += amounts.get(g * 3 + 2);
            }
            result.put("amountHT",  round2(totalHt));
            result.put("tva",       round2(totalTva));
            result.put("amountTTC", round2(totalTtc));
            if (groups >= 2) {
                result.put("tva2", round2(amounts.get(4)));
                log.info("Ventilation multi-taux: {} taux, HT={}, TVA={}, TTC={}",
                        groups, round2(totalHt), round2(totalTva), round2(totalTtc));
            }
        }

        return result;
    }

    /**
     * Extrait les montants HT depuis un tableau de TVA structuré
     * Format: | TAUX | MONTANT TTC | DONT TAXE | MONTANT HT |
     *         | 20   | 6866.94     | 1144.49   | 5722.45    |
     *
     * @param ocrText Le texte OCR complet
     * @return La somme des montants HT extraits depuis le tableau TVA
     */
    private Double extractHtFromTvaTable(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return null;
        }

        log.debug("=== Extraction HT depuis tableau TVA ===");

        // Pattern 1: Détecter l'en-tête du tableau TVA
        // "TAUX TVA" ou "TAUX | MONTANT T.T.C | DONT TAXE | MONTANT H.T"
        Pattern headerPattern = Pattern.compile(
                "(?i)(?:TAUX|Taux|Taux)\\s*(?:TVA|T\\.?V\\.?A\\.?)?\\s*\\|?\\s*(?:MONTANT\\s*)?(?:T\\.?T\\.?C\\.?|TTC)\\s*\\|?\\s*(?:DONT\\s*)?(?:TAXE|TVA)\\s*\\|?\\s*(?:MONTANT\\s*)?(?:H\\.?T\\.?|HT)",
                Pattern.MULTILINE
        );

        Matcher headerMatcher = headerPattern.matcher(ocrText);
        if (!headerMatcher.find()) {
            log.debug("En-tête de tableau TVA non trouvé");
            return null;
        }

        log.debug("En-tête de tableau TVA détecté");

        // Pattern 2: Extraire les lignes du tableau
        // Format: "20 | 6866.94 | 1144.49 | 5722.45"
        // ou: "00 | 61234.61 | 0.00 | 61234.61"
        Pattern rowPattern = Pattern.compile(
                "(?m)^\\h*(\\d{1,2})\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||$)"
        );

        Matcher rowMatcher = rowPattern.matcher(ocrText);
        double totalHt = 0.0;
        int rowCount = 0;

        while (rowMatcher.find()) {
            String taux = rowMatcher.group(1).trim();
            String montantTtc = normalizeAmountString(rowMatcher.group(2));
            String montantTva = normalizeAmountString(rowMatcher.group(3));
            String montantHt = normalizeAmountString(rowMatcher.group(4));

            Double htValue = parseAmount(montantHt);
            if (htValue != null && htValue > 0) {
                totalHt += htValue;
                rowCount++;
                log.debug("Ligne TVA {}: HT={}, TVA={}, TTC={}", taux, htValue, parseAmount(montantTva), parseAmount(montantTtc));
            }
        }

        if (rowCount > 0) {
            log.info("Tableau TVA extrait: {} lignes, Total HT={}", rowCount, totalHt);
            return round2(totalHt);
        }

        log.debug("Aucune ligne HT valide trouvée dans le tableau TVA");
        return null;
    }

    /**
     * Extrait le total TVA depuis un tableau de TVA structuré
     *
     * @param ocrText Le texte OCR complet
     * @return La somme des TVA extraites depuis le tableau
     */
    private Double extractTvaFromTvaTable(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return null;
        }

        Pattern rowPattern = Pattern.compile(
                "(?m)^\\h*(\\d{1,2})\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||\\h{2,})\\h*([\\d\\h,.]+?)\\h*(?:\\||$)"
        );

        Matcher rowMatcher = rowPattern.matcher(ocrText);
        double totalTva = 0.0;
        int rowCount = 0;

        while (rowMatcher.find()) {
            String montantTva = normalizeAmountString(rowMatcher.group(3));
            Double tvaValue = parseAmount(montantTva);

            if (tvaValue != null && tvaValue > 0) {
                totalTva += tvaValue;
                rowCount++;
            }
        }

        if (rowCount > 0) {
            log.debug("Tableau TVA: Total TVA extrait={}", totalTva);
            return round2(totalTva);
        }

        return null;
    }

    /**
     * Normalise une chaîne de caractères représentant un montant
     * Gère les espaces, virgules, points
     */
    private String normalizeAmountString(String value) {
        if (value == null || value.isBlank()) {
            return "0.00";
        }
        return value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\h+", "")
                .replace(',', '.');
    }

    private String normalizeValue(String fieldName, String value) {
        if (value == null)
            return null;

        switch (fieldName) {
            case "ice":
                return normalizeIdentifierValue(value, 15, 15);
            case "ifNumber":
                return normalizeIdentifierValue(value, 7, 10);
            case "rcNumber":
                return normalizeIdentifierValue(value, 4, 10);
            case "amountHT":
            case "tva":
            case "amountTTC":
                Double parsed = parseAmount(value);
                return parsed != null ? formatAmount(parsed) : value;
            case "invoiceDate":
                return normalizeInvoiceDate(value);
            case "invoiceNumber":
                String candidate = value.trim();
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (!candidate.matches(".*\\d.*")) {
                    return null;
                }
                if (lower.equals("avoir") || lower.equals("credit") || lower.equals("facture")) {
                    return null;
                }
                return candidate;
            case "supplier":
                return normalizeSupplier(value);
            default:
                return value.trim();
        }
    }

    private String normalizeSupplier(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }

        Matcher companyMatcher = Pattern.compile(
                "(?i)([A-Z][A-Z0-9\\s,&.'\\-]{2,80}\\b(?:SARL|S\\.A\\.R\\.L|SAS|SA|S\\.A)\\b)")
                .matcher(cleaned);
        String lastCompany = null;
        while (companyMatcher.find()) {
            lastCompany = companyMatcher.group(1).trim();
        }
        if (lastCompany != null && !lastCompany.isBlank()) {
            String normalizedCompany = lastCompany.replaceAll("\\s+", " ").trim();
            String[] noiseTokens = { "ESPECES", "REGLEMENT", "EE", "RE", "RC", "CNSS" };
            Set<String> noise = new HashSet<>(Arrays.asList(noiseTokens));
            List<String> tokens = new ArrayList<>(Arrays.asList(normalizedCompany.split("\\s+")));
            while (!tokens.isEmpty()) {
                String token = tokens.get(0).replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
                if (token.length() <= 2 || noise.contains(token)) {
                    tokens.remove(0);
                    continue;
                }
                break;
            }
            if (!tokens.isEmpty()) {
                return String.join(" ", tokens).trim();
            }
            return normalizedCompany;
        }

        return cleaned;
    }

    private String normalizeIdentifierValue(String raw, int minDigits, int maxDigits) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim()
                .replace('O', '0')
                .replace('o', '0')
                .replaceAll("[^0-9]", "");
        if (cleaned.length() < minDigits || cleaned.length() > maxDigits) {
            return cleaned.isBlank() ? null : cleaned;
        }
        return cleaned;
    }

    private void enforcePreferredInvoiceNumber(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }

        ExtractionAttempt preferred = tryPatterns(sourceText, Arrays.asList(
                "(?im)\\bN\\s*[^A-Za-z0-9]{0,3}\\s*de\\s*Facture\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                "(?im)\\b(?:BL\\s*/\\s*)?(?:Facture|FACTURE|Invoice|INVOICE)\\s*N\\s*[^A-Za-z0-9]{0,5}\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                "(?im)\\b(?:Facture|FACTURE|Invoice|INVOICE)\\b\\s*(?:N\\s*[Â°Âºo]?|No\\.?|#|:)\\s*([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                "(?im)\\b(?:Facture|FACTURE|Invoice|INVOICE)\\b\\s+([A-Z0-9][A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));

        if (preferred.value == null || preferred.value.isBlank()) {
            return;
        }

        String preferredNormalized = normalizeValue("invoiceNumber", preferred.value);
        if (preferredNormalized == null || preferredNormalized.isBlank()) {
            return;
        }

        DynamicExtractionResult.ExtractedField existingField = extracted.get("invoiceNumber");
        String existing = existingField != null ? String.valueOf(existingField.getValue()) : null;

        if (existing == null || existing.isBlank()) {
            addExtractedField(extracted, "invoiceNumber", preferredNormalized, preferred.confidence);
            return;
        }

        if (isLikelyReferenceNumber(existing) && !isLikelyReferenceNumber(preferredNormalized)) {
            addExtractedField(extracted, "invoiceNumber", preferredNormalized, preferred.confidence);
            log.info("Numero facture priorise: {} (au lieu de {})", preferredNormalized, existing);
        }
    }

    private boolean isLikelyReferenceNumber(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d{1,4}/\\d{1,4}")) {
            return true;
        }
        return !normalized.matches(".*[A-Z].*");
    }

    private String normalizeInvoiceDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String cleaned = raw.trim();
        cleaned = cleaned.replaceAll("[Oo]", "0");
        cleaned = cleaned.replaceAll("[Il]", "1");
        cleaned = cleaned.replaceAll("[^0-9/\\-.\\s]", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        Matcher separated = Pattern.compile("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{2,4})").matcher(cleaned);
        if (separated.find()) {
            int day = Integer.parseInt(separated.group(1));
            int month = Integer.parseInt(separated.group(2));
            int year = Integer.parseInt(separated.group(3));
            if (year < 100) {
                int currentYear = java.time.LocalDate.now().getYear();
                int candidateYear = 2000 + year;
                if (candidateYear > currentYear + 1 || candidateYear < currentYear - 15) {
                    return null;
                }
                year = candidateYear;
            }
            if (isPlausibleDate(day, month, year)) {
                return String.format(Locale.US, "%02d/%02d/%04d", day, month, year);
            }
        }

        String digits = cleaned.replaceAll("[^0-9]", "");
        if (digits.length() == 10 && digits.charAt(2) == '1' && digits.charAt(5) == '1') {
            digits = "" + digits.charAt(0) + digits.charAt(1) + digits.charAt(3) + digits.charAt(4) + digits.substring(6);
        }
        if (digits.length() == 8) {
            int day = Integer.parseInt(digits.substring(0, 2));
            int month = Integer.parseInt(digits.substring(2, 4));
            int year = Integer.parseInt(digits.substring(4, 8));
            if (isPlausibleDate(day, month, year)) {
                return String.format(Locale.US, "%02d/%02d/%04d", day, month, year);
            }
        }

        return null;
    }

    private boolean isPlausibleDate(int day, int month, int year) {
        return day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 2000 && year <= 2100;
    }

    private String extractDateFromLabeledContext(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern.compile(
                "(?im)(?:\\bDate\\b|\\bDATE\\b|\\bLe\\b)\\s*[:\\-]?\\s*([^\\n]{0,40})")
                .matcher(text);

        while (matcher.find()) {
            String window = matcher.group(1);
            if (window == null || window.isBlank()) {
                continue;
            }

            Matcher dateMatcher = Pattern.compile("([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{2,4})")
                    .matcher(window);
            if (dateMatcher.find()) {
                String dateCandidate = dateMatcher.group(1) + "/" + dateMatcher.group(2) + "/" + dateMatcher.group(3);
                String normalized = normalizeInvoiceDate(dateCandidate);
                if (normalized != null && normalized.matches("\\d{2}/\\d{2}/\\d{4}")) {
                    return normalized;
                }
            }
        }

        return null;
    }

    private String extractDateFromHeaderHeuristic(String headerText) {
        if (headerText == null || headerText.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern.compile("(?<!\\d)([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{1,2})\\s*[/\\-.]\\s*([0-9OIl]{2,4})(?!\\d)")
                .matcher(headerText);

        while (matcher.find()) {
            String raw = matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3);
            String normalized = normalizeInvoiceDate(raw);
            if (normalized != null && normalized.matches("\\d{2}/\\d{2}/\\d{4}")) {
                return normalized;
            }
        }

        return null;
    }

    private Double parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String cleaned = raw.trim()
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", "");

        cleaned = cleaned.replaceAll("(?<=\\d)[Oo](?=[\\d,\\.])", "0");
        cleaned = cleaned.replaceAll("(?<=[\\d,\\.])[Oo](?=\\d)", "0");
        cleaned = cleaned.replaceAll("(?<=\\d)[Il](?=\\d)", "1");
        cleaned = cleaned.replaceAll("[^0-9,\\.\\-]", "");

        if (cleaned.isBlank() || "-".equals(cleaned)) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "");
                cleaned = cleaned.replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }

        int firstDot = cleaned.indexOf('.');
        while (firstDot != -1 && firstDot != cleaned.lastIndexOf('.')) {
            cleaned = cleaned.substring(0, firstDot) + cleaned.substring(firstDot + 1);
            firstDot = cleaned.indexOf('.');
        }

        Double parsed = parseCanonicalAmount(cleaned);
        if (parsed != null) {
            return parsed;
        }

        String withoutDuplicatedLeadingDigit = removeDuplicatedLeadingDigit(cleaned);
        if (withoutDuplicatedLeadingDigit != null) {
            return parseCanonicalAmount(withoutDuplicatedLeadingDigit);
        }

        return null;
    }

    private Double parseCanonicalAmount(String cleaned) {
        try {
            return round2(Double.parseDouble(cleaned));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String removeDuplicatedLeadingDigit(String cleaned) {
        if (cleaned == null || cleaned.length() < 5 || cleaned.charAt(0) != cleaned.charAt(1)
                || !Character.isDigit(cleaned.charAt(0))) {
            return null;
        }
        return cleaned.substring(1);
    }

    private Double tryCorrectDuplicatedLeadingDigitAmount(
            Map<String, DynamicExtractionResult.ExtractedField> extracted,
            String fieldName,
            Double currentValue,
            Double expectedValue,
            List<String> missing,
            List<String> lowConfidence) {
        if (currentValue == null || expectedValue == null || expectedValue <= 0) {
            return currentValue;
        }
        if (!isLikelyDuplicatedLeadingDigitAmount(currentValue, expectedValue)) {
            return currentValue;
        }

        upsertAmountField(extracted, fieldName, expectedValue, 0.90, "OCR_DUPLICATED_LEADING_DIGIT_CORRECTION");
        missing.remove(fieldName);
        addLowConfidenceField(lowConfidence, fieldName);
        log.warn("{} corrige apres detection d'un chiffre duplique en tete: {} -> {}",
                fieldName, currentValue, expectedValue);
        return expectedValue;
    }

    private boolean isLikelyDuplicatedLeadingDigitAmount(Double currentValue, Double expectedValue) {
        if (currentValue == null || expectedValue == null || currentValue <= expectedValue || expectedValue <= 0) {
            return false;
        }
        String current = formatAmount(currentValue);
        String expected = formatAmount(expectedValue);
        return current.length() == expected.length() + 1
                && current.substring(1).equals(expected)
                && current.charAt(0) == current.charAt(1);
    }

    private String formatAmount(double value) {
        String s = String.format(Locale.US, "%.4f", round4(value));
        // Strip trailing zeros but keep at least 2 decimal places
        s = s.replaceAll("0+$", "");
        int dotIndex = s.indexOf('.');
        if (dotIndex >= 0) {
            int decimals = s.length() - dotIndex - 1;
            if (decimals < 2) {
                s = s + "0".repeat(2 - decimals);
            }
        }
        return s;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void addMissingField(List<String> missing, String fieldName) {
        if (!missing.contains(fieldName)) {
            missing.add(fieldName);
        }
    }

    private void addLowConfidenceField(List<String> lowConfidence, String fieldName) {
        if (!lowConfidence.contains(fieldName)) {
            lowConfidence.add(fieldName);
        }
    }

    private ExtractionAttempt extractField(String text, DynamicFieldDefinitionJson def) {
        if (def.getRegexPattern() == null || def.getRegexPattern().isBlank()) {
            return new ExtractionAttempt(null, 0.0);
        }

        try {
            Pattern pattern = Pattern.compile(def.getRegexPattern(), Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                return new ExtractionAttempt(value.trim(), 0.9);
            }
        } catch (Exception e) {
            log.warn("Regex invalide pour {} : {}", def.getFieldName(), e.getMessage());
        }

        return new ExtractionAttempt(null, 0.0);
    }

    private static class ExtractionAttempt {
        String value;
        double confidence;

        ExtractionAttempt(String value, double confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    private static class AmountCandidate {
        final String normalizedValue;
        final double numericValue;
        final double score;

        AmountCandidate(String normalizedValue, double numericValue, double score) {
            this.normalizedValue = normalizedValue;
            this.numericValue = numericValue;
            this.score = score;
        }
    }

    private String extractSupplierSmart(String header, String footer, String fullText) {
        List<String> candidates = new ArrayList<>();
        collectSupplierCandidate(candidates, extractSupplierFromHeader(header));
        collectSupplierCandidate(candidates, extractSupplierFromFooter(footer));
        collectSupplierCandidate(candidates, extractSupplierGeneric(fullText));
        collectSupplierCandidate(candidates, extractSupplierByFooterLine(fullText));
        collectSupplierCandidate(candidates, extractSupplierFromProminentLines(header));
        collectSupplierCandidate(candidates, extractSupplierFromProminentLines(footer));

        return selectBestSupplierCandidate(candidates);
    }

    private void collectSupplierCandidate(List<String> candidates, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String cleaned = candidate.replaceAll("\\s+", " ").trim();
        if (isValidSupplierCandidate(cleaned)) {
            candidates.add(cleaned);
        }
    }

    private String selectBestSupplierCandidate(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String candidate : candidates) {
            double score = scoreSupplierCandidate(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private String extractSupplierByFooterLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            if (containsSupplierSignal(line)) {
                return line;
            }
            if (i > 0) {
                String combined = (lines[i - 1] == null ? "" : lines[i - 1].trim()) + " " + line;
                if (containsSupplierSignal(combined) && isValidSupplierCandidate(combined)) {
                    return combined.replaceAll("\\s+", " ").trim();
                }
            }
        }
        return null;
    }

    private String extractSupplierFromProminentLines(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] lines = text.split("\\R");
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            if (!containsSupplierSignal(line)) {
                continue;
            }
            if (!isValidSupplierCandidate(line)) {
                continue;
            }
            double score = scoreSupplierCandidate(line);
            if (line.length() > 12) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }

    private boolean containsSupplierSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("SARL")
                || upper.contains("S.A.R.L")
                || upper.contains("SA")
                || upper.contains("SAS")
                || upper.contains("SOCIETE")
                || upper.contains("COMPANY")
                || upper.contains("GROUP")
                || upper.contains("MAGASIN")
                || upper.contains("STE ")
                || upper.matches(".*\\b[A-Z]{4,}\\b.*");
    }

    private double scoreSupplierCandidate(String candidate) {
        if (candidate == null) {
            return Double.NEGATIVE_INFINITY;
        }
        String normalized = candidate.toUpperCase(Locale.ROOT);
        double score = candidate.length();
        if (normalized.contains("SARL")) score += 25;
        if (normalized.contains("S.A.R.L")) score += 20;
        if (normalized.contains("SA")) score += 15;
        if (normalized.contains("SAS")) score += 15;
        if (normalized.contains("COMPANY") || normalized.contains("SOCIETE")) score += 8;
        if (normalized.matches(".*\\b[A-Z]{2,}\\b.*")) score += 5;
        return score;
    }

    private String extractSupplierFromHeader(String header) {
        if (header == null || header.isBlank())
            return null;

        Pattern deBlockPattern = Pattern.compile(
                "(?im)(?:^|\\n)\\s*(?:DE|FROM)\\s*\\n+\\s*([A-Z][A-Za-z0-9\\s&.,'()\\-/]{2,60})");
        Matcher deBlockMatcher = deBlockPattern.matcher(header);
        if (deBlockMatcher.find()) {
            String candidate = deBlockMatcher.group(1).trim();
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        Pattern multiLinePattern = Pattern.compile(
                "^\\s*([A-Z][A-Z\\s]{2,40})\\s*(?:\\n|\\r\\n)+\\s*([A-Z][A-Z\\s]{2,40})",
                Pattern.MULTILINE);

        Matcher matcher = multiLinePattern.matcher(header);
        if (matcher.find()) {
            String line1 = matcher.group(1).trim();
            String line2 = matcher.group(2).trim();
            String candidate = (line1 + " " + line2).replaceAll("\\s+", " ");
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        Pattern singleLinePattern = Pattern.compile(
                "^\\s*([A-Z][A-Z\\s&]{3,50})\\s*$",
                Pattern.MULTILINE);

        matcher = singleLinePattern.matcher(header);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (isValidSupplierCandidate(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private String extractSupplierGeneric(String text) {
        if (text == null || text.isBlank())
            return null;

        Pattern deInlinePattern = Pattern.compile(
                "(?i)(?:^|\\n)\\s*(?:DE|FROM)\\s*[:\\-]?\\s*([A-Z][A-Za-z0-9\\s&.,'()\\-/]{2,60})");
        Matcher deInlineMatcher = deInlinePattern.matcher(text);
        if (deInlineMatcher.find()) {
            String supplier = deInlineMatcher.group(1).trim();
            if (isValidSupplierCandidate(supplier)) {
                return supplier;
            }
        }

        Pattern pattern = Pattern.compile(
                "([A-Z][A-Z\\s&]{3,50})(?:\\s+(?:SARL|SAS|SA|S\\.A\\.R\\.L|S\\.A))?",
                Pattern.MULTILINE);

        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String supplier = matcher.group(1).trim();
            if (isValidSupplierCandidate(supplier)) {
                return supplier;
            }
        }

        return null;
    }

    private boolean isValidSupplierCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String normalized = candidate.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 80) {
            return false;
        }

        String[] forbidden = {
                "FACTURE", "INVOICE", "TOTAL", "TAXE", "TAX", "DRAFT",
                "FACTURE A", "FACTURÃ‰ A", "FACTURE Ã€", "BILL TO", "ARTICLE",
                "DESCRIPTION", "QTE", "QTÃ‰", "PRIX", "MONTANT", "DATE",
                "ESPECES", "REGLEMENT", "CNSS"
        };
        for (String token : forbidden) {
            if (normalized.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private String sanitizeTextForSupplierIdentifiers(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String normalizedText = text.replace("\\", "");

        StringBuilder out = new StringBuilder();
        int skipLines = 0;
        for (String line : normalizedText.split("\\r?\\n")) {
            if (skipLines > 0) {
                if (line.trim().isEmpty()) {
                    skipLines = 0;
                } else {
                    skipLines--;
                }
                continue;
            }

            if (line.matches("(?i).*\\b(Nom\\s*de\\s*client|Client|Factur[eÃ©]\\s*[aÃ ]|Factur[eÃ©]\\s*Ã |FACTUR[EÃ‰]\\s*[AÃ€]|BILL\\s+TO|CUSTOMER)\\b.*")) {
                skipLines = 5;
                continue;
            }

            out.append(line).append('\n');
        }

        return out.toString();
    }

    private String extractIfLoose(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] patterns = {
                "(?i)(?:1\\.?\\s*F\\.?|I\\.?\\s*F\\.?|IF|L\\.?\\s*F\\.?|LF|L\\.?\\s*E\\.?|LE)\\s*[:.]?\\s*([0-9\\s\\.]{6,20})",
                "(?i)(?:I\\.?\\s*F\\.?|IF|L\\.?\\s*F\\.?|LF|L\\.?\\s*E\\.?|LE)\\s*[:.]?\\s*([0-9\\s\\.]{6,20})",
                "(?i)(?:1\\.?\\s*F\\.?|IF|LF|LE)\\s*(?:N\\s*[°ºo]?\\s*)?[:.]?\\s*([0-9\\s\\.]{6,20})"
        };
        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            while (matcher.find()) {
                String candidate = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                if (candidate == null) {
                    continue;
                }
                String digits = candidate.replaceAll("\\D", "");
                if (digits.length() >= 7 && digits.length() <= 10) {
                    return digits;
                }
            }
        }
        return null;
    }

    private String extractIceLoose(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern pattern = Pattern.compile("(?i)(?:I\\.?\\s*C\\.?\\s*E|ICE|1CE|LCE|LC\\.E)\\s*[:.]?\\s*([0-9\\s\\.]{10,30})");
        Matcher matcher = pattern.matcher(text);
        String bestDigits = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null) {
                continue;
            }
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                double score = scoreIceCandidate(digits, matcher.start());
                if (score > bestScore) {
                    bestScore = score;
                    bestDigits = digits;
                }
            }
        }

        return bestDigits;
    }

    private String extractIceByProximity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern keywordPattern = Pattern.compile("(?i)(?:I\\s*\\.?\\s*C\\s*\\.?\\s*E|ICE|1CE|LCE)");
        Matcher keywordMatcher = keywordPattern.matcher(text);
        String bestDigits = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        while (keywordMatcher.find()) {
            int start = keywordMatcher.end();
            int end = Math.min(text.length(), start + 100);
            String window = text.substring(start, end);
            Matcher numberMatcher = Pattern.compile("([0-9\\s\\.]{10,30})").matcher(window);
            while (numberMatcher.find()) {
                String candidate = numberMatcher.group(1);
                if (candidate == null) {
                    continue;
                }
                String digits = candidate.replaceAll("\\D", "");
                if (digits.length() == 15) {
                    double score = scoreIceCandidate(digits, keywordMatcher.start());
                    if (score > bestScore) {
                        bestScore = score;
                        bestDigits = digits;
                    }
                }
            }
        }

        // Fallback: any 15-digit sequence in footer-like text
        Matcher loose = Pattern.compile("([0-9\\s\\.]{10,30})").matcher(text);
        while (loose.find()) {
            String candidate = loose.group(1);
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                double score = scoreIceCandidate(digits, loose.start());
                if (score > bestScore) {
                    bestScore = score;
                    bestDigits = digits;
                }
            }
        }

        return bestDigits;
    }

    private double scoreIceCandidate(String digits, int position) {
        double score = 0.0;
        if (digits != null && digits.length() == 15) {
            score += 100.0;
        }
        if (position >= 0) {
            score += Math.max(0, 50.0 - Math.min(position / 200.0, 50.0));
        }
        String prefix = digits != null && digits.length() >= 3 ? digits.substring(0, 3) : "";
        if ("000".equals(prefix) || "001".equals(prefix)) {
            score += 5.0;
        }
        return score;
    }

    private String extractLastMatchStrict(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String lastMatch = null;
        int lastPosition = -1;

        for (String patternStr : patterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);

                while (matcher.find()) {
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();

                    if (value != null && !value.isBlank()) {
                        String normalized = value.replaceAll("\\s+", "");

                        if (!normalized.isEmpty()) {
                            int position = matcher.start();

                            if (position > lastPosition) {
                                lastMatch = normalized;
                                lastPosition = position;
                                log.debug("Nouveau match trouvÃƒÆ’Ã‚Â© ÃƒÆ’Ã‚Â  position {}: {} (pattern: {})",
                                        position, lastMatch, patternStr);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("Erreur avec le pattern '{}': {}", patternStr, e.getMessage());
            }
        }

        if (lastMatch != null) {
            log.debug("DERNIER match retenu ÃƒÆ’Ã‚Â  position {}: {}", lastPosition, lastMatch);
        } else {
            log.debug("Aucun match trouvÃƒÆ’Ã‚Â© dans tous les patterns");
        }

        return lastMatch;
    }

    /**
     * ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ NOUVELLE MÃƒÆ’Ã¢â‚¬Â°THODE: Extrait le texte d'une zone spÃƒÆ’Ã‚Â©cifique en utilisant les
     * marqueurs
     * Exemple: extractZoneText(ocrText, "FOOTER") retourne le texte entre [FOOTER]
     * et la fin/prochain marqueur
     */
    private String extractZoneText(String ocrText, String zoneName) {
        if (ocrText == null || ocrText.isBlank()) {
            return "";
        }

        String startMarker = "[" + zoneName + "]";
        int startIndex = ocrText.indexOf(startMarker);

        if (startIndex == -1) {
            log.debug("Marqueur {} non trouvÃƒÆ’Ã‚Â© dans le texte OCR", startMarker);
            return null; // Pas de marqueur trouvÃƒÆ’Ã‚Â©
        }

        // Commencer aprÃƒÆ’Ã‚Â¨s le marqueur
        startIndex += startMarker.length();

        // Trouver le prochain marqueur de zone (ou fin du texte)
        int endIndex = ocrText.length();

        // Chercher les autres marqueurs de zone
        String[] otherZones = { "[HEADER]", "[BODY]", "[FOOTER]" };
        for (String otherMarker : otherZones) {
            if (otherMarker.equals(startMarker))
                continue;

            int otherIndex = ocrText.indexOf(otherMarker, startIndex);
            if (otherIndex != -1 && otherIndex < endIndex) {
                endIndex = otherIndex;
            }
        }

        String zoneText = ocrText.substring(startIndex, endIndex).trim();
        log.debug("Zone {} extraite: {} caractÃƒÆ’Ã‚Â¨res", zoneName, zoneText.length());

        return zoneText;
    }

    /**
     * Tente d'extraire ICE, IF et RC ensemble s'ils apparaissent dans le mÃƒÆ’Ã‚Âªme
     * bloc/ligne
     */
    private void extractUnifiedIdentifiers(String text, Map<String, DynamicExtractionResult.ExtractedField> extracted) {
        // Pattern spÃƒÆ’Ã‚Â©cifique pour le format demandÃƒÆ’Ã‚Â©: "NÃƒâ€šÃ‚Â° ICE: ... NÃƒâ€šÃ‚Â° RC: ... IF NÃƒâ€šÃ‚Â°:
        // ..."
        // On gÃƒÆ’Ã‚Â¨re l'ordre variable car ÃƒÆ’Ã‚Â§a peut changer selon l'OCR
        String[] combinedPatterns = {
                // ICE -> RC -> IF
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // ICE -> IF -> RC
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // RC -> ICE -> IF
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // RC -> IF -> ICE
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15})",
                // IF -> ICE -> RC
                "(?i)IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // IF -> RC -> ICE
                "(?i)IF\\s*(?:NÃƒâ€šÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15})"
        };

        for (String patternStr : combinedPatterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                log.info("ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ Bloc identifiant unifiÃƒÆ’Ã‚Â© trouvÃƒÆ’Ã‚Â©!");

                String block = matcher.group(0);

                String ice = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.ICE_PATTERNS));
                String ifNum = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.IF_PATTERNS));
                String rc = extractLastMatchStrict(block, Arrays.asList(ExtractionPatterns.RC_PATTERNS));

                if (ice != null)
                    addExtractedField(extracted, "ice", ice.replaceAll("\\s+", ""), 0.98);
                if (ifNum != null)
                    addExtractedField(extracted, "ifNumber", ifNum.replaceAll("\\s+", ""), 0.98);
                if (rc != null)
                    addExtractedField(extracted, "rcNumber", rc.replaceAll("\\s+", ""), 0.98);

                return;
            }
        }
    }
}





