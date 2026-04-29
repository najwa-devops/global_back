package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrait les totaux débit/crédit depuis le texte OCR d'un relevé bancaire.
 *
 * Stratégies (dans l'ordre) :
 *  1. Ligne avec "TOTAL" + "DEBIT" + "CREDIT" → 2 montants sur la même ligne
 *  2. Ligne avec "TOTAL MOUVEMENTS" → 2 montants sur la même ligne
 *  3. Lignes "TOTAL DEBIT" / "TOTAL CREDIT" séparées
 *     - Si la ligne contient des montants → prise directe
 *     - Sinon → look-ahead sur les 2 lignes suivantes (SAHAM : montants sur la ligne suivante)
 *  4. Fallback BMCE : aucun "TOTAL" trouvé → dernière ligne avec 2 montants avant "NOUVEAU SOLDE"
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementTotalsExtractor {

    private final OcrCleaningService cleaningService;

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d+(?:[\\s\\.]\\d{3})*[,\\.]\\d{2}");

    public Totals extractTotals(String text) {
        if (text == null || text.isBlank()) {
            return new Totals(null, null);
        }

        String[] lines = text.split("\n");
        BigDecimal totalDebit  = null;
        BigDecimal totalCredit = null;

        for (int i = 0; i < lines.length; i++) {
            String line  = lines[i];
            String upper = line.toUpperCase();

            if (!upper.contains("TOTAL")) continue;

            // Skip generic noise lines that have no debit/credit signal
            boolean hasDebitSignal  = upper.contains("DEBIT")  || upper.contains("DÉBIT");
            boolean hasCreditSignal = upper.contains("CREDIT") || upper.contains("CRÉDIT");
            boolean hasSolde        = upper.contains("SOLDE");
            boolean hasCapital      = upper.contains("CAPITAL");
            boolean hasSA           = upper.contains("S.A");

            // Filter out noise ONLY when the line has no debit/credit signal at all
            if (!hasDebitSignal && !hasCreditSignal) {
                if (hasSolde || hasCapital || hasSA) continue;
            }

            List<BigDecimal> amounts = extractAmountsFromLine(line);

            // ── Case 1 : both DEBIT and CREDIT on the same TOTAL line ────────
            if (hasDebitSignal && hasCreditSignal) {
                if (amounts.size() >= 2) {
                    totalDebit  = amounts.get(amounts.size() - 2);
                    totalCredit = amounts.get(amounts.size() - 1);
                    log.debug("Totals (DEBIT+CREDIT line): D={} C={}", totalDebit, totalCredit);
                    break;
                }
                // No amounts on this line → look-ahead (handles cases where
                // "Total débit  Total crédit" header has amounts on next line)
                List<BigDecimal> next = lookAheadAmounts(lines, i, 2);
                if (next.size() >= 2) {
                    if (next.size() >= 3) {
                        totalDebit = next.get(next.size() - 2);
                        totalCredit = next.get(next.size() - 1);
                    } else {
                        totalDebit = next.get(0);
                        totalCredit = next.get(1);
                    }
                    log.debug("Totals (DEBIT+CREDIT look-ahead): D={} C={}", totalDebit, totalCredit);
                    break;
                }
                continue;
            }

            // ── Case 2 : "TOTAL MOUVEMENTS" / "TOTAL DES MOUVEMENTS" ─────────
            if (upper.contains("TOTAL MOUVEMENTS") || upper.contains("TOTAL DES MOUVEMENTS")) {
                if (amounts.size() >= 2) {
                    totalDebit  = amounts.get(amounts.size() - 2);
                    totalCredit = amounts.get(amounts.size() - 1);
                    log.debug("Totals (TOTAL MOUVEMENTS): D={} C={}", totalDebit, totalCredit);
                    break;
                }
                List<BigDecimal> next = lookAheadAmounts(lines, i, 2);
                if (next.size() >= 2) {
                    totalDebit  = next.get(0);
                    totalCredit = next.get(1);
                }
                break;
            }

            // ── Case 3a : isolated "TOTAL DEBIT" line ────────────────────────
            if (hasDebitSignal && totalDebit == null) {
                if (!amounts.isEmpty()) {
                    totalDebit = amounts.get(amounts.size() - 1);
                    log.debug("TotalDebit (inline): {}", totalDebit);
                } else {
                    // look-ahead: SAHAM puts amounts on the next line
                    List<BigDecimal> next = lookAheadAmounts(lines, i, 2);
                    if (!next.isEmpty()) {
                        // If next line has ≥ 2 amounts, first is debit total
                        totalDebit = next.get(0);
                        // If it also has a second amount and we have no credit yet,
                        // treat it as credit (some banks combine both on one amounts line)
                        if (next.size() >= 2 && totalCredit == null) {
                            totalCredit = next.get(1);
                            log.debug("Totals (DEBIT look-ahead 2-in-1): D={} C={}", totalDebit, totalCredit);
                        } else {
                            log.debug("TotalDebit (look-ahead): {}", totalDebit);
                        }
                    }
                }
                continue;
            }

            // ── Case 3b : isolated "TOTAL CREDIT" line ───────────────────────
            if (hasCreditSignal && totalCredit == null) {
                if (!amounts.isEmpty()) {
                    totalCredit = amounts.get(amounts.size() - 1);
                    log.debug("TotalCredit (inline): {}", totalCredit);
                } else {
                    List<BigDecimal> next = lookAheadAmounts(lines, i, 2);
                    if (!next.isEmpty()) {
                        totalCredit = next.get(next.size() - 1);
                        log.debug("TotalCredit (look-ahead): {}", totalCredit);
                    }
                }
            }
        }

        // ── Fallback : BMCE — find last 2-amount line before "NOUVEAU SOLDE" ─
        if (totalDebit == null && totalCredit == null) {
            Totals fallback = bmceFallback(lines);
            if (fallback.totalDebitPdf != null || fallback.totalCreditPdf != null) {
                log.debug("Totals (BMCE fallback): D={} C={}", fallback.totalDebitPdf, fallback.totalCreditPdf);
                return fallback;
            }
        }

        return new Totals(totalDebit, totalCredit);
    }

    /**
     * Scans up to {@code maxLines} lines after line {@code fromIndex} and returns
     * all amounts found in the first non-empty line that contains at least one amount.
     */
    private List<BigDecimal> lookAheadAmounts(String[] lines, int fromIndex, int maxLines) {
        for (int j = fromIndex + 1; j < lines.length && j <= fromIndex + maxLines; j++) {
            String candidate = lines[j].trim();
            if (candidate.isEmpty()) continue;
            List<BigDecimal> amounts = extractAmountsFromLine(candidate);
            if (!amounts.isEmpty()) return amounts;
        }
        return List.of();
    }

    /**
     * BMCE fallback: no TOTAL keyword found anywhere.
     * Scan backward from the first "NOUVEAU SOLDE" line; return the last line
     * that contains exactly 2 amounts — these are the debit and credit totals.
     */
    private Totals bmceFallback(String[] lines) {
        int nouveauSoldeIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toUpperCase().contains("NOUVEAU SOLDE")
                    || lines[i].toUpperCase().contains("SOLDE FINAL")
                    || lines[i].toUpperCase().contains("SOLDE EN FIN")) {
                nouveauSoldeIndex = i;
                break;
            }
        }
        if (nouveauSoldeIndex <= 0) return new Totals(null, null);

        // Walk backwards from that line to find a line with exactly 2 amounts
        for (int i = nouveauSoldeIndex - 1; i >= 0; i--) {
            String candidate = lines[i].trim();
            if (candidate.isEmpty()) continue;
            // Skip balance/header lines
            String up = candidate.toUpperCase();
            if (up.contains("SOLDE") || up.contains("DATE") || up.contains("LIBELLE")) continue;

            List<BigDecimal> amounts = extractAmountsFromLine(candidate);
            if (amounts.size() == 2) {
                return new Totals(amounts.get(0), amounts.get(1));
            }
        }
        return new Totals(null, null);
    }

    private List<BigDecimal> extractAmountsFromLine(String line) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher matcher = AMOUNT_PATTERN.matcher(line);
        while (matcher.find()) {
            String raw = matcher.group();
            try {
                BigDecimal value = new BigDecimal(cleaningService.normalizeAmount(raw));
                if (value.compareTo(BigDecimal.ZERO) < 0) {
                    value = value.abs();
                }
                amounts.add(value);
            } catch (Exception ignored) {
            }
        }
        return amounts;
    }

    public static class Totals {
        public final BigDecimal totalDebitPdf;
        public final BigDecimal totalCreditPdf;

        public Totals(BigDecimal totalDebitPdf, BigDecimal totalCreditPdf) {
            this.totalDebitPdf = totalDebitPdf;
            this.totalCreditPdf = totalCreditPdf;
        }
    }
}
