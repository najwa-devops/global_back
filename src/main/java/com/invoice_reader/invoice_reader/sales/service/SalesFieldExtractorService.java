package com.invoice_reader.invoice_reader.sales.service;

import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicTemplate.DynamicFieldDefinitionJson;
import com.invoice_reader.invoice_reader.sales.utils.SalesExtractionPatterns;
import com.invoice_reader.invoice_reader.utils.ExtractionPatterns;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class SalesFieldExtractorService {
    private static final List<String> SALES_ICE_PATTERNS = mergePatterns(
            SalesExtractionPatterns.ICE_PATTERNS,
            ExtractionPatterns.ICE_PATTERNS);
    private static final List<String> SALES_IF_PATTERNS = mergePatterns(
            SalesExtractionPatterns.IF_PATTERNS,
            ExtractionPatterns.IF_PATTERNS);
    private static final List<String> SALES_RC_PATTERNS = mergePatterns(
            SalesExtractionPatterns.RC_PATTERNS,
            ExtractionPatterns.RC_PATTERNS);

    public SalesExtractionResult extractWithTemplate(String ocrText, DynamicTemplate template) {
        long start = System.currentTimeMillis();

        Map<String, SalesExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        for (DynamicFieldDefinitionJson field : template.getFieldDefinitions()) {
            ExtractionAttempt attempt = extractField(ocrText, field);

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
                    SalesExtractionResult.ExtractedField.builder()
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

        return SalesExtractionResult.builder()
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

    public SalesExtractionResult extractWithoutTemplate(String ocrText) {
        long start = System.currentTimeMillis();

        log.info("Extraction sans template - utilisation des patterns par dÃƒÂ©faut");

        Map<String, SalesExtractionResult.ExtractedField> extracted = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        // Ã¢Å“â€¦ CORRECTION: Utiliser les marqueurs de zones si disponibles
        String header = extractZoneText(ocrText, "HEADER");
        String body = extractZoneText(ocrText, "BODY");
        String footer = extractZoneText(ocrText, "FOOTER");

        // Fallback si pas de marqueurs
        if (header == null || header.isBlank()) {
            header = getHeader(ocrText); // Premier 30%
        }
        if (footer == null || footer.isBlank()) {
            footer = getFooter(ocrText); // Dernier 50%
        }
        if (body == null || body.isBlank()) {
            body = ocrText; // Tout le texte comme fallback
        }

        log.debug("Zones extraites - Header: {} chars, Body: {} chars, Footer: {} chars",
                header.length(), body.length(), footer.length());

        // ===================== EXTRACTION HEADER =====================

        // AMÃƒâ€°LIORATION: NumÃƒÂ©ro de facture (patterns plus flexibles)
        extractAndAdd(extracted, missing, "invoiceNumber", header, Arrays.asList(
                // Pattern - user format: "BL/FACTURE N° ..."
                "(?im)^\\s*BL\\s*/\\s*FACTURE\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 0: "Numéro de facture : 105/2025"
                "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 1: "Facture 2026-FA050" (votre cas exact)
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([0-9]{4}-[A-Z]{2}[0-9]+)\\b",

                // Pattern 2: "Facture NÃ‚Â° XXX" avec NÃ‚Â°
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?(?:N\\s*[°ºo]?|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 2b: "INV-CLI-0005" / "INV-2026-001"
                "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",

                // Pattern 3: "FACTURE: XXX" avec deux-points
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s*(?:Avoir\\s+)?[:\\s]+([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3b: "Codafiin Facture POS-26-00002"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 3c: "( FACTURE N° : 250182 )"
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?(?:N\\s*[°ºo]?|No\\.?|#|:)\\s*([0-9]{3,})\\b",

                // Pattern 3d: "FACTURE N░ : 250182" (OCR N° variantes)
                "(?im)^.*\\b(?:Facture|FACTURE|Invoice)\\b\\s*(?:Avoir\\s+)?N\\s*[^A-Za-z0-9]{0,3}\\s*[:\\-]?\\s*([0-9]{3,})\\b",

                // Pattern 4: Format annÃƒÂ©e-lettres-chiffres (2026-FA050, 2024-INV001)
                "\\b([0-9]{4}-[A-Z]{2,}[0-9]+)\\b",

                // Pattern 5: GÃƒÂ©nÃƒÂ©rique aprÃƒÂ¨s "Facture"
                "(?im)^\\s*(?:Facture|FACTURE|Invoice)\\s+(?:Avoir\\s+)?([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 6: "Correction facture : IN2602-0001"
                "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",

                // Pattern 7: "N° 12345" (header short form)
                "(?im)^\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));

        if (!extracted.containsKey("invoiceNumber")) {
            ExtractionAttempt invoiceFallback = tryPatterns(ocrText, Arrays.asList(
                    "(?im)^\\s*BL\\s*/\\s*FACTURE\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "\\b(INV(?:OICE)?-[A-Z0-9]{2,}(?:-[A-Z0-9]{2,})+)\\b",
                    "(?im)^\\s*(?:FACTURE|Facture|INVOICE|Invoice)\\s*(?:Avoir\\s+)?(?:N[°ºo]|No\\.?|#|:)\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Num\\p{L}*\\s+de\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*Correction\\s+facture\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b",
                    "(?im)^\\s*(?:N\\s*[°ºo]?|No\\.?)\\s*[:\\-]?\\s*([A-Z0-9\\-/]*\\d[A-Z0-9\\-/]*)\\b"));
            if (invoiceFallback.value != null && !invoiceFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceNumber", invoiceFallback.value);
                addExtractedField(extracted, "invoiceNumber", normalized, invoiceFallback.confidence);
                missing.remove("invoiceNumber");
            }
        }



        // AMÃƒâ€°LIORATION: Date facture (cherche "Date facturation")
        extractAndAdd(extracted, missing, "invoiceDate", header, Arrays.asList(
                // Pattern 1: "Date facturation : 09/02/2026" (votre cas)
                "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 2: "Date de facturation:"
                "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 3: "Date facture:"
                "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 4: "Date:" suivi d'une date
                "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",

                // Pattern 5: Date avec sÃ©parateur obligatoire (Ã©vite ICE)
                "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",

                // Pattern 6: "le 31/01/2026"
                "(?im)\\ble\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})"));

        if (!extracted.containsKey("invoiceDate")) {
            ExtractionAttempt dateFallback = tryPatterns(ocrText, Arrays.asList(
                    "Date\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+de\\s+facturation\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s+(?:de\\s+)?facture\\s*[:'`.\\-\\s]*([0-9OIl\\s/\\-.]{8,16})",
                    "Date\\s*[:'`.\\-\\s]+([0-9OIl\\s/\\-.]{8,16})",
                    "([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})",
                    "(?im)\\ble\\s*[:\\-]?\\s*([0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{2}\\s*[/\\-.]\\s*[0-9OIl]{4})"));
            if (dateFallback.value != null && !dateFallback.value.isBlank()) {
                String normalized = normalizeValue("invoiceDate", dateFallback.value);
                addExtractedField(extracted, "invoiceDate", normalized, dateFallback.confidence);
                missing.remove("invoiceDate");
            }
        }

        // ===================== EXTRACTION FOURNISSEUR (TEXTE COMPLET)
        // =====================

        String sanitizedFooter = sanitizeTextForSupplierIdentifiers(footer);
        String sanitizedFullText = sanitizeTextForSupplierIdentifiers(ocrText);
        String sanitizedHeader = sanitizeTextForSupplierIdentifiers(header);

        // Ã¢Å“â€¦ NOUVEAU: Extraction unifiÃƒÂ©e (si les trois sont sur la mÃƒÂªme ligne/bloc)
        extractUnifiedIdentifiers(sanitizedFooter, extracted);

        // ICE du FOURNISSEUR: en vente, on retient le PREMIER ICE détecté.
        if (!extracted.containsKey("ice")) {
            String ice = extractFirstMatchStrict(sanitizedFooter, SALES_ICE_PATTERNS);

            if (ice == null) {
                ice = extractFirstMatchStrict(sanitizedHeader, SALES_ICE_PATTERNS);
            }

            if (ice == null) {
                ice = extractFirstMatchStrict(sanitizedFullText, SALES_ICE_PATTERNS);
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
                ice = extractIceByProximity(ocrText);
            }

            if (ice != null) {
                ice = ice.replaceAll("\\s+", "");
                if (ice.matches("\\d{15}")) {
                    addExtractedField(extracted, "ice", ice, 0.95);
                    log.info("Ã¢Å“â€¦ ICE fournisseur extrait depuis FOOTER: {}", ice);
                } else {
                    log.warn("ICE invalide (longueur != 15): {}", ice);
                    missing.add("ice");
                }
            } else {
                log.warn("Ã¢ÂÅ’ Aucun ICE trouvÃƒÂ© dans FOOTER");
                missing.add("ice");
            }
        }

        // IF du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("ifNumber")) {
            String ifNumber = extractLastMatchStrict(sanitizedFooter, SALES_IF_PATTERNS);

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedHeader, SALES_IF_PATTERNS);
            }

            if (ifNumber == null) {
                ifNumber = extractLastMatchStrict(sanitizedFullText, SALES_IF_PATTERNS);
            }

            if (ifNumber != null) {
                addExtractedField(extracted, "ifNumber", ifNumber, 0.95);
                log.info("IF fournisseur extrait: {}", ifNumber);
            } else {
                log.info("Aucun IF trouvÃƒÂ© dans le footer (optionnel en vente)");
            }
        }

        // RC du FOURNISSEUR (FOOTER UNIQUEMENT)
        if (!extracted.containsKey("rcNumber")) {
            String rc = extractLastMatchStrict(sanitizedFooter, SALES_RC_PATTERNS);

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedHeader, SALES_RC_PATTERNS);
            }

            if (rc == null) {
                rc = extractLastMatchStrict(sanitizedFullText, SALES_RC_PATTERNS);
            }

            if (rc != null) {
                addExtractedField(extracted, "rcNumber", rc, 0.95);
                log.info("RC fournisseur extrait depuis FOOTER: {}", rc);
            } else {
                log.info("Aucun RC trouvÃƒÂ© dans FOOTER (optionnel en vente)");
            }
        }

        // SUPPLIER - Smart extraction using all zones
        String supplier = extractSupplierSmart(header, footer, ocrText);
        if (supplier != null) {
            addExtractedField(extracted, "supplier", supplier, 0.95);
            log.info("Supplier extrait (smart): {}", supplier);
        } else {
            missing.add("supplier");
        }

        // ===================== EXTRACTION MONTANTS =====================

        // AMÃƒâ€°LIORATION: Montant HT (gÃƒÂ¨re virgule ET point) - Cherche dans BODY ou OCR
        String totalsPriorityText = buildTotalsPriorityText(footer, body, ocrText);
        Map<String, String> totalsByLabel = extractTotalsByLabel(totalsPriorityText);
        Map<String, Double> ventilationAmounts = extractVentilationTvaAmounts(totalsPriorityText + "\n" + ocrText);
        if (ventilationAmounts.containsKey("amountHT")) {
            addCalculatedAmount(extracted, "amountHT", ventilationAmounts.get("amountHT"), "TVA_VENTILATION_BLOCK");
            missing.remove("amountHT");
            log.info("HT extrait via bloc ventilation TVA: {}", ventilationAmounts.get("amountHT"));
        }
        if (ventilationAmounts.containsKey("tva")) {
            addCalculatedAmount(extracted, "tva", ventilationAmounts.get("tva"), "TVA_VENTILATION_BLOCK");
            missing.remove("tva");
            log.info("TVA extraite via bloc ventilation TVA: {}", ventilationAmounts.get("tva"));
        }
        if (ventilationAmounts.containsKey("amountTTC")) {
            addCalculatedAmount(extracted, "amountTTC", ventilationAmounts.get("amountTTC"), "TVA_VENTILATION_BLOCK");
            missing.remove("amountTTC");
            log.info("TTC extrait via bloc ventilation TVA: {}", ventilationAmounts.get("amountTTC"));
        }
        addAmountFromLabeledTotals(extracted, missing, "amountHT", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "tva", totalsByLabel);
        addAmountFromLabeledTotals(extracted, missing, "amountTTC", totalsByLabel);

        if (!extracted.containsKey("amountHT")) {
            extractAmountWithFallback(extracted, missing, "amountHT", totalsPriorityText, ocrText, Arrays.asList(

                // Pattern 1: Matches "Total HT" or "Total H.T." or "Total Hors Taxes"
                // Examples:
                // "Total HT 448,00"
                // "Total H.T. : 448,00"
                // "Total Hors Taxes - 1 234,56"
                "(?i)Total\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 2: Matches "Montant HT" or "Montant Hors Taxes"
                // Example: "Montant HT: 448,00"
                "(?i)Montant\\s+(?:H\\.?T\\.?|Hors\\s+Taxe[s]?)\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

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
                "(?i)Sous[-\\s]?total\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?"
            ));
        }



        // AMÃƒâ€°LIORATION: TVA (gÃƒÂ¨re "Total TVA 20% 89,60")
        if (!extracted.containsKey("tva")) {
            extractAmountWithFallback(extracted, missing, "tva", totalsPriorityText, ocrText, Arrays.asList(
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
                "(?i)Total\\s+tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?",

                // Pattern 7: "taxes : 7,00"
                "(?i)^\\s*tax(?:e|es|s)?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?\\s*$"
            ));
        }

        // AMÃƒâ€°LIORATION: Montant TTC (gÃƒÂ¨re point dÃƒÂ©cimal "537.60")
        if (!extracted.containsKey("amountTTC")) {
            extractAmountWithFallback(extracted, missing, "amountTTC", totalsPriorityText, ocrText, Arrays.asList(
                // Pattern 1: "Total TTC 537.60" (votre cas - point dÃƒÂ©cimal)
                "(?i)(?:Total\\s+T\\.?T\\.?C\\.?|IRE|TFC)\\s+([\\d\\s]+[,.]\\d{2})",

                // Pattern 2: "Total T.T.C. : 537.60"
                "(?i)(?:Total\\s*T\\.?T\\.?C\\.?|IRE|TFC)\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 3: "Net ÃƒÂ  payer: 537.60"
                "Net\\s*[ÃƒÂ a]\\s*payer\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 4: "Montant TTC:"
                "Montant\\s*T\\.?T\\.?C\\.?\\s*[:\\s]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 4b: "Montant total: 537.60" / "montant payé: 537.60"
                "(?i)Montant\\s+total\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?",
                "(?i)Montant\\s+pay[ée]\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?",
                "(?i)Montant\\s+total\\s+T\\.?T\\.?C\\.?\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",
                "(?i)Total\\s+net\\s+a\\s+payer\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})",

                // Pattern 5: Dans un tableau
                "Total\\s*T\\.?T\\.?C\\.?[\\s\\n]+([\\d\\s]+[,.]\\d{2})",

                // Pattern 6: "TOTAL : 42.00 MAD"
                "(?i)^\\s*TOTAL\\s*[:\\-]?\\s*([\\d\\s]+[,.]\\d{2})\\s*(?:MAD|DH|€)?\\s*$"));
        }

        // ===================== VALIDATION TTC = HT + TVA =====================

        // Calculer TTC attendu si HT et TVA sont disponibles
        SalesExtractionResult.ExtractedField htField = extracted.get("amountHT");
        SalesExtractionResult.ExtractedField tvaField = extracted.get("tva");
        SalesExtractionResult.ExtractedField ttcField = extracted.get("amountTTC");

        Double ht = parseAmountField(htField);
        Double tva = parseAmountField(tvaField);
        Double ttc = parseAmountField(ttcField);

        ht = tryCorrectDuplicatedLeadingDigitAmount(extracted, "amountHT", ht,
                ttc != null && tva != null ? round2(ttc - tva) : null, missing, lowConfidence);
        tva = tryCorrectDuplicatedLeadingDigitAmount(extracted, "tva", tva,
                ttc != null && ht != null ? round2(ttc - ht) : null, missing, lowConfidence);
        ttc = tryCorrectDuplicatedLeadingDigitAmount(extracted, "amountTTC", ttc,
                ht != null && tva != null ? round2(ht + tva) : null, missing, lowConfidence);

        if (ht == null && tva != null && ttc != null) {
            double calculatedHT = round2(ttc - tva);
            if (calculatedHT >= 0) {
                log.info("HT manquant, calcul automatique: {} - {} = {}", ttc, tva, calculatedHT);
                addCalculatedAmount(extracted, "amountHT", calculatedHT, "CALCULATED_FROM_TTC_MINUS_TVA");
                missing.remove("amountHT");
                ht = calculatedHT;
            }
        }

        if (tva == null && ht != null && ttc != null) {
            double calculatedTVA = round2(ttc - ht);
            if (calculatedTVA >= 0) {
                log.info("TVA manquante, calcul automatique: {} - {} = {}", ttc, ht, calculatedTVA);
                addCalculatedAmount(extracted, "tva", calculatedTVA, "CALCULATED_FROM_TTC_MINUS_HT");
                missing.remove("tva");
                tva = calculatedTVA;
            }
        }

        if (ttc == null && ht != null && tva != null) {
            double calculatedTTC = round2(ht + tva);
            log.info("TTC manquant, calcul automatique: {} + {} = {}", ht, tva, calculatedTTC);
            addCalculatedAmount(extracted, "amountTTC", calculatedTTC, "CALCULATED_FROM_HT_PLUS_TVA");
            missing.remove("amountTTC");
            ttc = calculatedTTC;
        }

        if (ht != null && tva != null && ttc != null) {
            double calculatedTTC = round2(ht + tva);
            double difference = Math.abs(calculatedTTC - ttc);

            if (difference > 0.01) {
                log.warn("Incoherence TTC detectee: extrait={}, calcule (HT+TVA)={}, diff={}",
                        ttc, calculatedTTC, difference);

                extracted.put("ttcCalculated",
                        SalesExtractionResult.ExtractedField.builder()
                                .value(formatAmount(calculatedTTC))
                                .normalizedValue(formatAmount(calculatedTTC))
                                .confidence(1.0)
                                .detectionMethod("CALCULATED")
                                .validated(true)
                                .validationError("TTC extrait (" + formatAmount(ttc)
                                        + ") != HT+TVA (" + formatAmount(calculatedTTC) + ")")
                                .build());

                addLowConfidenceField(lowConfidence, "amountTTC");
            } else {
                log.info("Validation TTC OK: {} = {} + {}", ttc, ht, tva);
            }
        }

        // Calculer les mÃƒÂ©triques
        boolean complete = missing.isEmpty() || missing.size() <= 2;
        double overallConfidence = extracted.isEmpty()
                ? 0.0
                : extracted.values().stream()
                        .mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0.0)
                        .average()
                        .orElse(0.0);

        log.info("Extraction terminÃƒÂ©e: {} champs extraits, {} manquants, confiance {}%",
                extracted.size(), missing.size(), Math.round(overallConfidence * 100));

        return SalesExtractionResult.builder()
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
            Map<String, SalesExtractionResult.ExtractedField> extracted,
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
            Map<String, SalesExtractionResult.ExtractedField> extracted,
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
            Map<String, SalesExtractionResult.ExtractedField> extracted,
            String fieldName,
            String value,
            double confidence) {
        extracted.put(fieldName,
                SalesExtractionResult.ExtractedField.builder()
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
                            log.debug("Match trouvÃƒÂ© avec pattern '{}': {}", patternStr, lastMatch);
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
                "Si[eÃƒÂ¨]ge\\s*social\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]{2,50}?)\\s*[-Ã¢â‚¬â€œ]",
                Pattern.CASE_INSENSITIVE);
        Matcher siegeMatcher = siegePattern.matcher(footer);
        if (siegeMatcher.find()) {
            String supplier = siegeMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÂ© via 'SiÃƒÂ¨ge social': {}", supplier);
            return supplier;
        }

        Pattern proprietairePattern = Pattern.compile(
                "(?:Nom\\s*du\\s*propriÃƒÂ©taire|Titulaire)\\s*(?:du\\s*compte)?\\s*[:\\s]*([A-Z][A-Za-z0-9\\s&.,''()-]+(?:SARL|SAS|SA|S\\.A\\.R\\.L))",
                Pattern.CASE_INSENSITIVE);
        Matcher proprietaireMatcher = proprietairePattern.matcher(footer);
        if (proprietaireMatcher.find()) {
            String supplier = proprietaireMatcher.group(1).trim();
            log.debug("Supplier trouvÃƒÂ© via 'Nom du propriÃƒÂ©taire': {}", supplier);
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
            log.debug("Supplier trouvÃƒÂ© via SARL/SA pattern: {}", lastSaMatch);
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

                    log.debug("Pattern match trouvÃƒÂ©: '{}' Ã¢â€ â€™ valeur: '{}'",
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
        String context = text.substring(contextStart, contextEnd).toLowerCase(Locale.ROOT);

        double score = 0.85 - (patternIndex * 0.03);

        if (context.contains("total")) {
            score += 0.10;
        }
        if (context.contains("montant")) {
            score += 0.05;
        }
        if (context.contains("net a payer") || context.contains("net Ã  payer")) {
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
            Map<String, SalesExtractionResult.ExtractedField> extracted,
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
        for (int i = startIndex; i < Math.min(lines.length, startIndex + 24); i++) {
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
            if (amounts.size() >= 3) {
                break;
            }
        }

        if (amounts.size() >= 3) {
            result.put("amountHT", round2(amounts.get(0)));
            result.put("tva", round2(amounts.get(1)));
            result.put("amountTTC", round2(amounts.get(2)));
        }

        return result;
    }

    private String detectTotalField(String line) {
        String raw = line == null ? "" : line.toLowerCase(Locale.ROOT);
        String normalized = raw
                .replace(" ", "")
                .replace(".", "");

        if (raw.matches(".*\\b(?:ire|tfc)\\b.*")) {
            return "amountTTC";
        }
        if (raw.matches(".*\\biva\\b.*")) {
            return "tva";
        }
        if (raw.matches(".*\\b(?:tt|mi)\\b.*")) {
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

    private String extractAmountToken(String line) {
        Matcher matcher = Pattern.compile("(\\d[\\d\\s]{0,20}[,.]\\d{2})").matcher(line);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private String normalizeTextForMatching(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
            default:
                return value.trim();
        }
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
                year += 2000;
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

        return raw.trim();
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

    private boolean isPlausibleDate(int day, int month, int year) {
        return day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 2000 && year <= 2100;
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
            Map<String, SalesExtractionResult.ExtractedField> extracted,
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

        addCalculatedAmount(extracted, fieldName, expectedValue, "OCR_DUPLICATED_LEADING_DIGIT_CORRECTION");
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

    private Double parseAmountField(SalesExtractionResult.ExtractedField field) {
        if (field == null) {
            return null;
        }
        if (field.getNormalizedValue() instanceof Number n) {
            return round2(n.doubleValue());
        }
        return parseAmount(String.valueOf(field.getNormalizedValue()));
    }

    private void addCalculatedAmount(
            Map<String, SalesExtractionResult.ExtractedField> extracted,
            String fieldName,
            double value,
            String method) {
        String normalized = formatAmount(value);
        extracted.put(fieldName,
                SalesExtractionResult.ExtractedField.builder()
                        .value(normalized)
                        .normalizedValue(normalized)
                        .confidence(0.94)
                        .detectionMethod(method)
                        .validated(true)
                        .build());
    }

    private String formatAmount(double value) {
        return String.format(Locale.US, "%.2f", round2(value));
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
        String supplier = extractSupplierFromHeader(header);
        if (supplier != null)
            return supplier;

        supplier = extractSupplierFromFooter(footer);
        if (supplier != null)
            return supplier;

        supplier = extractSupplierGeneric(fullText);
        return supplier;
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
                "FACTURE A", "FACTURÉ A", "FACTURE À", "BILL TO", "ARTICLE",
                "DESCRIPTION", "QTE", "QTÉ", "PRIX", "MONTANT", "DATE"
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

            if (line.matches("(?i).*\\b(Nom\\s*de\\s*client|Client|Factur[eé]\\s*[aà]|Factur[eé]\\s*à|FACTUR[EÉ]\\s*[AÀ]|BILL\\s+TO|CUSTOMER)\\b.*")) {
                skipLines = 5;
                continue;
            }

            out.append(line).append('\n');
        }

        return out.toString();
    }

    private String extractIceLoose(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern pattern = Pattern.compile("(?i)(?:I\\.?\\s*C\\.?\\s*E|ICE|1CE|LCE)\\s*[:.]?\\s*([0-9\\s\\.]{10,30})");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null) {
                continue;
            }
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                return digits;
            }
        }

        return null;
    }

    private String extractIceByProximity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern keywordPattern = Pattern.compile("(?i)(?:I\\s*\\.?\\s*C\\s*\\.?\\s*E|ICE|1CE|LCE)");
        Matcher keywordMatcher = keywordPattern.matcher(text);
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
                    return digits;
                }
            }
        }

        // Fallback: any 15-digit sequence in footer-like text
        Matcher loose = Pattern.compile("([0-9\\s\\.]{10,30})").matcher(text);
        while (loose.find()) {
            String candidate = loose.group(1);
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() == 15) {
                return digits;
            }
        }

        return null;
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
                                log.debug("Nouveau match trouvÃƒÂ© ÃƒÂ  position {}: {} (pattern: {})",
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
            log.debug("DERNIER match retenu ÃƒÂ  position {}: {}", lastPosition, lastMatch);
        } else {
            log.debug("Aucun match trouvÃƒÂ© dans tous les patterns");
        }

        return lastMatch;
    }

    private String extractFirstMatchStrict(String text, List<String> patterns) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String firstMatch = null;
        int firstPosition = Integer.MAX_VALUE;

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

                            if (position < firstPosition) {
                                firstMatch = normalized;
                                firstPosition = position;
                                log.debug("Nouveau premier match trouvé à position {}: {} (pattern: {})",
                                        position, firstMatch, patternStr);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("Erreur avec le pattern '{}': {}", patternStr, e.getMessage());
            }
        }

        if (firstMatch != null) {
            log.debug("PREMIER match retenu à position {}: {}", firstPosition, firstMatch);
        } else {
            log.debug("Aucun match trouvé dans tous les patterns");
        }

        return firstMatch;
    }

    private static List<String> mergePatterns(String[]... patternGroups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String[] group : patternGroups) {
            if (group == null) {
                continue;
            }
            merged.addAll(Arrays.asList(group));
        }
        return new ArrayList<>(merged);
    }

    /**
     * Ã¢Å“â€¦ NOUVELLE MÃƒâ€°THODE: Extrait le texte d'une zone spÃƒÂ©cifique en utilisant les
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
            log.debug("Marqueur {} non trouvÃƒÂ© dans le texte OCR", startMarker);
            return null; // Pas de marqueur trouvÃƒÂ©
        }

        // Commencer aprÃƒÂ¨s le marqueur
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
        log.debug("Zone {} extraite: {} caractÃƒÂ¨res", zoneName, zoneText.length());

        return zoneText;
    }

    /**
     * Tente d'extraire ICE, IF et RC ensemble s'ils apparaissent dans le mÃƒÂªme
     * bloc/ligne
     */
    private void extractUnifiedIdentifiers(String text, Map<String, SalesExtractionResult.ExtractedField> extracted) {
        // Pattern spÃƒÂ©cifique pour le format demandÃƒÂ©: "NÃ‚Â° ICE: ... NÃ‚Â° RC: ... IF NÃ‚Â°:
        // ..."
        // On gÃƒÂ¨re l'ordre variable car ÃƒÂ§a peut changer selon l'OCR
        String[] combinedPatterns = {
                // ICE -> RC -> IF
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // ICE -> IF -> RC
                "(?i)ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // RC -> ICE -> IF
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10})",
                // RC -> IF -> ICE
                "(?i)RC\\s*[:.]?\\s*(\\d{4,10}).*?IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15})",
                // IF -> ICE -> RC
                "(?i)IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?ICE\\s*[:.]?\\s*(\\d{15}).*?RC\\s*[:.]?\\s*(\\d{4,10})",
                // IF -> RC -> ICE
                "(?i)IF\\s*(?:NÃ‚Â°)?\\s*[:.]?\\s*(\\d{7,10}).*?RC\\s*[:.]?\\s*(\\d{4,10}).*?ICE\\s*[:.]?\\s*(\\d{15})"
        };

        for (String patternStr : combinedPatterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                log.info("Ã¢Å“â€¦ Bloc identifiant unifiÃƒÂ© trouvÃƒÂ©!");

                String block = matcher.group(0);

                String ice = extractFirstMatchStrict(block, SALES_ICE_PATTERNS);
                String ifNum = extractLastMatchStrict(block, SALES_IF_PATTERNS);
                String rc = extractLastMatchStrict(block, SALES_RC_PATTERNS);

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





