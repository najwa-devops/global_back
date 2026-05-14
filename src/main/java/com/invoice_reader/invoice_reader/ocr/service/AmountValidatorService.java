package com.invoice_reader.invoice_reader.ocr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SERVICE DE VALIDATION DES MONTANTS
 *
 * Vérifie la cohérence TTC = HT + TVA avec une tolérance de 5 centimes.
 * Calcule automatiquement le champ manquant si deux champs sont connus.
 *
 * Remplace la logique de isTTCConsistent() (tolérance 0.01) dans AchatInvoice.
 * L'ancienne méthode reste pour compatibilité ascendante.
 */
@Service
public class AmountValidatorService {

    private static final Logger log = LoggerFactory.getLogger(AmountValidatorService.class);

    private static final double TOLERANCE = 0.05; // 5 centimes

    // Clés dans fieldsData (map JSON de AchatInvoice)
    private static final String KEY_HT  = "amountHT";
    private static final String KEY_TVA = "tva";
    private static final String KEY_TTC = "amountTTC";
    private static final String KEY_TVA_RATE = "tvaRate";
    private static final String KEY_COMPUTED_FIELDS = "computedAmountFields";
    private static final String KEY_AMOUNT_SOURCE = "amountCalculationSource";
    private static final String KEY_TVA_RATE_SOURCE = "tvaRateSource";
    private static final String KEY_TVA_RATE_USED = "tvaRateUsed";
    private static final String KEY_AMOUNT_HT_SOURCE = "amountHTSource";
    private static final String KEY_TVA_SOURCE = "tvaSource";
    private static final String KEY_TTC_SOURCE = "amountTTCSource";

    /**
     * Résultat de validation des montants.
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String message;
        public final Double correctedHT;
        public final Double correctedTVA;
        public final Double correctedTTC;

        public ValidationResult(boolean valid, String message,
                                Double correctedHT, Double correctedTVA, Double correctedTTC) {
            this.valid = valid;
            this.message = message;
            this.correctedHT  = correctedHT;
            this.correctedTVA = correctedTVA;
            this.correctedTTC = correctedTTC;
        }
    }

    /**
     * Valide la cohérence des trois montants.
     *
     * - Si les 3 sont présents : vérifie |TTC - (HT + TVA)| <= 0.05
     *   → Si incohérent : TTC fait foi, HT est recalculé
     * - Si 2 présents : calcule le 3ème automatiquement
     * - Si < 2 présents : valid=true (rien à valider)
     */
    public ValidationResult validate(Double ht, Double tva, Double ttc) {
        int known = (ht != null ? 1 : 0) + (tva != null ? 1 : 0) + (ttc != null ? 1 : 0);

        if (known == 3) {
            double dHT  = ht.doubleValue();
            double dTVA = tva.doubleValue();
            double dTTC = ttc.doubleValue();
            double expected = round2(dHT + dTVA);
            if (Math.abs(expected - dTTC) <= TOLERANCE) {
                return new ValidationResult(true, null, ht, tva, ttc);
            } else {
                String msg = String.format(
                        "Incohérence : HT(%.2f) + TVA(%.2f) = %.2f ≠ TTC(%.2f)",
                        dHT, dTVA, expected, dTTC
                );
                log.debug("AmountValidatorService: {}", msg);
                // TTC fait foi — recalculer HT
                return new ValidationResult(false, msg, round2(dTTC - dTVA), tva, ttc);
            }
        }

        if (known == 2) {
            if (ttc == null) {
                double correctedTTC = round2(ht.doubleValue() + tva.doubleValue());
                return new ValidationResult(true, "TTC calculé automatiquement", ht, tva, correctedTTC);
            } else if (ht == null) {
                double correctedHT = round2(ttc.doubleValue() - tva.doubleValue());
                return new ValidationResult(true, "HT calculé automatiquement", correctedHT, tva, ttc);
            } else {
                double correctedTVA = round2(ttc.doubleValue() - ht.doubleValue());
                return new ValidationResult(true, "TVA calculée automatiquement", ht, correctedTVA, ttc);
            }
        }

        // Moins de 2 champs — rien à valider
        return new ValidationResult(true, null, ht, tva, ttc);
    }

    /**
     * Applique la validation directement sur la map fieldsData de AchatInvoice.
     * Lit les champs amountHT, tva, amountTTC, valide, et corrige le map si nécessaire.
     *
     * @return true si les montants sont cohérents (ou si pas assez de champs)
     */
    public boolean applyToFieldsData(Map<String, Object> fieldsData) {
        return applyToFieldsData(fieldsData, null);
    }

    public boolean applyToFieldsData(Map<String, Object> fieldsData, Double configuredTvaRate) {
        if (fieldsData == null) return true;

        Double ht  = toDouble(fieldsData.get(KEY_HT));
        Double tva = toDouble(fieldsData.get(KEY_TVA));
        Double ttc = toDouble(fieldsData.get(KEY_TTC));
        Double rate = configuredTvaRate != null ? configuredTvaRate : toDouble(fieldsData.get(KEY_TVA_RATE));
        String rateSource = configuredTvaRate != null
                ? "ACCOUNT"
                : String.valueOf(fieldsData.getOrDefault(KEY_TVA_RATE_SOURCE, "EXTRACTED"));
        List<String> computedAmountFields = new ArrayList<>();
        boolean changed = false;

        if (rate != null && ttc != null && ht == null && tva == null) {
            double normalizedRate = rate / 100.0;
            double correctedHT = round2(ttc / (1.0 + normalizedRate));
            double correctedTVA = round2(ttc - correctedHT);
            fieldsData.put(KEY_HT, correctedHT);
            fieldsData.put(KEY_TVA, correctedTVA);
            fieldsData.put(KEY_TVA_RATE, rate);
            fieldsData.put(KEY_TVA_RATE_USED, rate);
            fieldsData.put(KEY_TVA_RATE_SOURCE, rateSource);
            fieldsData.put(KEY_AMOUNT_SOURCE, "CALCULATED_FROM_TTC_AND_TVA_RATE");
            fieldsData.put(KEY_AMOUNT_HT_SOURCE, "CALCULATED_FROM_TTC_AND_TVA_RATE");
            fieldsData.put(KEY_TVA_SOURCE, "CALCULATED_FROM_TTC_AND_TVA_RATE");
            computedAmountFields.add(KEY_HT);
            computedAmountFields.add(KEY_TVA);
            changed = true;
            log.info("Montants deduits depuis TTC + taux TVA {}%: HT={}, TVA={}", rate, correctedHT, correctedTVA);
            pushComputedFields(fieldsData, computedAmountFields);
            return true;
        }

        ValidationResult result = validate(ht, tva, ttc);

        // Écrire les valeurs corrigées si elles ont été calculées automatiquement
        if (result.correctedTTC != null && !result.correctedTTC.equals(ttc)) {
            fieldsData.put(KEY_TTC, result.correctedTTC);
            fieldsData.put(KEY_TTC_SOURCE, "CALCULATED_FROM_HT_AND_TVA");
            computedAmountFields.add(KEY_TTC);
            changed = true;
        }
        if (result.correctedHT != null && !result.correctedHT.equals(ht)) {
            fieldsData.put(KEY_HT, result.correctedHT);
            fieldsData.put(KEY_AMOUNT_HT_SOURCE, "CALCULATED_FROM_TTC_AND_TVA");
            computedAmountFields.add(KEY_HT);
            changed = true;
        }
        if (result.correctedTVA != null && !result.correctedTVA.equals(tva)) {
            fieldsData.put(KEY_TVA, result.correctedTVA);
            fieldsData.put(KEY_TVA_SOURCE, "CALCULATED_FROM_TTC_AND_HT");
            computedAmountFields.add(KEY_TVA);
            changed = true;
        }

        if (rate != null) {
            fieldsData.put(KEY_TVA_RATE_USED, rate);
            fieldsData.put(KEY_TVA_RATE_SOURCE, rateSource);
        }
        if (changed && !computedAmountFields.isEmpty()) {
            fieldsData.put(KEY_AMOUNT_SOURCE, "CALCULATED");
        }
        pushComputedFields(fieldsData, computedAmountFields);
        return result.valid;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void pushComputedFields(Map<String, Object> fieldsData, List<String> computedAmountFields) {
        if (fieldsData == null) {
            return;
        }
        if (computedAmountFields == null || computedAmountFields.isEmpty()) {
            fieldsData.remove(KEY_COMPUTED_FIELDS);
            return;
        }
        Set<String> unique = new LinkedHashSet<>(computedAmountFields);
        fieldsData.put(KEY_COMPUTED_FIELDS, new ArrayList<>(unique));
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
