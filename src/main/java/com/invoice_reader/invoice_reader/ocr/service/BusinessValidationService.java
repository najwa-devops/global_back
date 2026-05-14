package com.invoice_reader.invoice_reader.ocr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
public class BusinessValidationService {

    private static final Pattern ICE_PATTERN = Pattern.compile("\\d{15}");
    private static final Pattern IF_PATTERN = Pattern.compile("\\d{6,10}");
    private static final Pattern RC_PATTERN = Pattern.compile("[A-Z0-9\\-/]{3,20}");

    public ValidationOutcome validate(Map<String, Object> fieldsData, String ocrText, boolean salesFlow) {
        List<String> anomalies = new ArrayList<>();
        List<String> weakFields = new ArrayList<>();
        Map<String, Double> fieldConfidences = new LinkedHashMap<>();
        Map<String, String> fieldSources = new LinkedHashMap<>();

        assessField("invoiceNumber", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("invoiceDate", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField(salesFlow ? "clientName" : "supplier", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("ice", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("ifNumber", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("rcNumber", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("amountHT", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("tva", fieldsData, fieldConfidences, fieldSources, weakFields);
        assessField("amountTTC", fieldsData, fieldConfidences, fieldSources, weakFields);

        validateFiscalField("ICE", asString(fieldsData.get("ice")), ICE_PATTERN, anomalies, weakFields, "ice");
        validateFiscalField("IF", asString(fieldsData.get("ifNumber")), IF_PATTERN, anomalies, weakFields, "ifNumber");
        validateFiscalField("RC", asString(fieldsData.get("rcNumber")), RC_PATTERN, anomalies, weakFields, "rcNumber");

        Double ht = parseAmount(fieldsData.get("amountHT"));
        Double tva = parseAmount(fieldsData.get("tva"));
        Double ttc = parseAmount(fieldsData.get("amountTTC"));
        if (ht != null && tva != null && ttc != null && Math.abs((ht + tva) - ttc) > 0.05) {
            anomalies.add("Incohérence montants: HT + TVA != TTC");
            weakFields.add("amountHT");
            weakFields.add("tva");
            weakFields.add("amountTTC");
            fieldConfidences.put("amountHT", 0.35);
            fieldConfidences.put("tva", 0.35);
            fieldConfidences.put("amountTTC", 0.35);
        }

        String uppercaseText = ocrText != null ? ocrText.toUpperCase(Locale.ROOT) : "";
        if (!uppercaseText.contains("FACTURE")) {
            anomalies.add("Mot-clé facture absent");
        }
        if (!uppercaseText.contains("TOTAL") && !uppercaseText.contains("TTC")) {
            anomalies.add("Mot-clé total absent");
        }
        if (!uppercaseText.contains("DATE")) {
            anomalies.add("Mot-clé date absent");
        }

        if (salesFlow) {
            if (isBlank(asString(fieldsData.get("clientName"))) && isBlank(asString(fieldsData.get("supplier")))) {
                anomalies.add("Client absent pour le flux vente");
                weakFields.add("clientName");
            }
        } else if (isBlank(asString(fieldsData.get("supplier")))) {
            anomalies.add("Fournisseur absent pour le flux achat");
            weakFields.add("supplier");
        }

        int coherenceScore = Math.max(0, 100 - (anomalies.size() * 12) - (new LinkedHashSet<>(weakFields).size() * 5));
        boolean reviewRequired = coherenceScore < 75 || !anomalies.isEmpty();

        return new ValidationOutcome(
                coherenceScore,
                new ArrayList<>(new LinkedHashSet<>(anomalies)),
                new ArrayList<>(new LinkedHashSet<>(weakFields)),
                fieldConfidences,
                fieldSources,
                reviewRequired
        );
    }

    private void assessField(
            String fieldName,
            Map<String, Object> fieldsData,
            Map<String, Double> fieldConfidences,
            Map<String, String> fieldSources,
            List<String> weakFields
    ) {
        String value = asString(fieldsData.get(fieldName));
        if (isBlank(value)) {
            fieldConfidences.put(fieldName, 0.2);
            fieldSources.put(fieldName, "OCR");
            weakFields.add(fieldName);
            return;
        }
        fieldConfidences.put(fieldName, 0.85);
        fieldSources.put(fieldName, "OCR");
    }

    private void validateFiscalField(
            String label,
            String value,
            Pattern pattern,
            List<String> anomalies,
            List<String> weakFields,
            String fieldName
    ) {
        if (isBlank(value)) {
            return;
        }
        String normalized = value.replaceAll("\\s+", "");
        if (!pattern.matcher(normalized).matches()) {
            anomalies.add("Format " + label + " invalide");
            weakFields.add(fieldName);
        }
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value).trim() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Double parseAmount(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).replace('\u00A0', ' ').replaceAll("\\s+", "").replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    public record ValidationOutcome(
            int coherenceScore,
            List<String> anomalies,
            List<String> weakFields,
            Map<String, Double> fieldConfidences,
            Map<String, String> fieldSources,
            boolean reviewRequired
    ) {
    }
}
