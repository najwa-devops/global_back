package com.invoice_reader.invoice_reader.ocr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SERVICE DE NETTOYAGE TEXTE OCR
 *
 * Nettoie le texte OCR brut avant l'extraction des champs.
 * Corrige les erreurs spécifiques aux factures marocaines.
 *
 * IMPORTANT : Préserve les marqueurs [HEADER], [BODY], [FOOTER]
 *             utilisés par AchatFieldExtractorService.
 */
@Service
public class TextCleaningService {

    private static final Logger log = LoggerFactory.getLogger(TextCleaningService.class);

    /**
     * Pipeline de nettoyage complet.
     * Applique les corrections dans l'ordre optimal pour les factures marocaines.
     */
    public String clean(String rawText) {
        if (rawText == null) return "";

        String t = rawText;

        // 1. Normaliser fins de ligne
        t = t.replaceAll("\r\n", "\n").replaceAll("\r", "\n");

        // 2. Supprimer lignes vides multiples (garder max 2 sauts de ligne consécutifs)
        t = t.replaceAll("\n{3,}", "\n\n");

        // 3. Supprimer espaces multiples sur une même ligne (garder \n)
        t = t.replaceAll("[ \t]+", " ");

        // 4. Normaliser mots-clés fiscaux marocains (corrige erreurs OCR sur points/espaces)
        t = t.replaceAll("(?i)T\\.\\s*T\\.\\s*C\\.?", "TTC");
        t = t.replaceAll("(?i)T7C\\b", "TTC");
        t = t.replaceAll("(?i)H\\.\\s*T\\.?(?![A-Z])", "HT");
        t = t.replaceAll("(?i)H7\\b", "HT");
        t = t.replaceAll("(?i)T\\.\\s*V\\.\\s*A\\.?", "TVA");
        t = t.replaceAll("(?i)1VA\\b", "TVA");
        t = t.replaceAll("(?i)lVA\\b", "TVA");
        t = t.replaceAll("(?i)I\\.\\s*C\\.\\s*E\\.?", "ICE");
        t = t.replaceAll("(?i)lCE\\b", "ICE");
        t = t.replaceAll("(?i)1\\.\\s*F\\.?", "IF");
        t = t.replaceAll("(?i)I\\.\\s*F\\.?", "IF");
        t = t.replaceAll("(?i)1\\.\\s*C\\.\\s*E\\.?", "ICE");
        t = t.replaceAll("(?i)IC\\.\\s*E\\.?", "ICE");
        t = t.replaceAll("(?i)1CE\\b", "ICE");
        t = t.replaceAll("(?i)TOTALNET\\s*A\\s*PAYER", "TOTAL NET A PAYER");
        t = t.replaceAll("(?i)TOTAL\\s*NET\\s*A\\s*PAYER", "TOTAL NET A PAYER");
        t = t.replaceAll("(?i)DONTTVA", "DONT TVA");
        t = t.replaceAll("(?i)DONT\\s*TVA", "DONT TVA");

        // 5. Normaliser N° (numéro de facture — très souvent mal lu)
        t = t.replaceAll("N\\s*[°oO0]\\s*[:\\.]?", "N° ");
        t = t.replaceAll("N-\\s", "N° ");

        // 6. Normaliser FACTURE et variantes OCR
        t = t.replaceAll("(?i)FACTIIRE", "FACTURE");
        t = t.replaceAll("(?i)FACTIJRE", "FACTURE");

        // 7. Corrections caractères dans les séquences numériques (l/I→1, o/O→0)
        t = t.replaceAll("(?<=\\d)[lI](?=\\d)", "1");
        t = t.replaceAll("(?<=\\d)[oO](?=\\d)", "0");
        t = t.replaceAll("(?<=\\d)[:;](?=\\d{2}\\b)", ".");

        // 8. Déléguer corrections additionnelles à OcrPostProcessor (composition)
        //    Note: postProcess() peut être appelé ici mais peut altérer des mots normaux.
        //    On l'applique seulement si le texte n'a pas déjà été post-traité.
        // t = ocrPostProcessor.postProcess(t); // décommentez si nécessaire

        // 9. Re-nettoyer espaces après toutes les substitutions
        t = t.replaceAll("[ \t]+", " ");

        // 10. Trim global (sans toucher aux \n internes)
        t = t.trim();

        log.debug("TextCleaningService: {} → {} chars après nettoyage", rawText.length(), t.length());
        return t;
    }

    /**
     * Alias de clean() — utilisé par les orchestrateurs.
     */
    public String getCleanedText(String rawText) {
        return clean(rawText);
    }

    /**
     * Parse un montant en format marocain → Double.
     *
     * Formats gérés :
     * - "1 200,00"   → 1200.00  (espace comme séparateur de milliers, virgule décimale)
     * - "1.200,00"   → 1200.00  (point séparateur de milliers, virgule décimale)
     * - "1,200.00"   → 1200.00  (virgule séparateur de milliers, point décimal — anglophone)
     * - "2033,64"    → 2033.64  (virgule décimale simple)
     * - "2033.64"    → 2033.64  (point décimal simple)
     * - "2000,00 Dh" → 2000.00  (avec unité monétaire)
     * - "333,1667"   → 333.1667 (plusieurs décimales)
     *
     * @return Double parsé, ou null si le texte est null/vide/non parseable
     */
    public Double parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;

        try {
            String cleaned = raw.trim()
                    .replaceAll("\\s+", "")              // supprimer espaces (séparateurs milliers)
                    .replaceAll("(?i)[Dd][Hh]$", "")     // supprimer "Dh" / "DH"
                    .replaceAll("(?i)[Mm][Aa][Dd]$", "") // supprimer "MAD"
                    .replaceAll("[€$]", "");              // supprimer devises

            // Cas "1.234,56" ou "1.234,1667" → séparateur milliers = point, décimal = virgule
            if (cleaned.matches("\\d{1,3}(\\.\\d{3})+,\\d+")) {
                cleaned = cleaned.replace(".", "").replace(",", ".");
            }
            // Cas "1,234.56" ou "1,234.1667" → séparateur milliers = virgule, décimal = point
            else if (cleaned.matches("\\d{1,3}(,\\d{3})+\\.\\d+")) {
                cleaned = cleaned.replace(",", "");
            }
            // Cas standard marocain avec virgule décimale : "2033,64" ou "333,1667"
            else if (cleaned.contains(",") && !cleaned.contains(".")) {
                cleaned = cleaned.replace(",", ".");
            }
            // Sinon : "2033.64" ou autre — laisser tel quel

            return Double.parseDouble(cleaned);

        } catch (NumberFormatException e) {
            log.debug("parseAmount: impossible de parser '{}'", raw);
            return null;
        }
    }

    /**
     * Retourne les lignes non vides du texte nettoyé.
     */
    public List<String> getLines(String cleanedText) {
        if (cleanedText == null) return List.of();
        return Arrays.stream(cleanedText.split("\n"))
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .collect(Collectors.toList());
    }
}
