package com.invoice_reader.invoice_reader.banque.service;
import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte la banque d'un relevé bancaire en deux étapes :
 *
 *  Étape 1 — Mots-clés dans les 40 premières lignes (en-tête)
 *             → rapide, suffisant dans 95 % des cas
 *
 *  Étape 2 — Code banque extrait du RIB (3 premiers chiffres d'un RIB 24 chiffres)
 *             → utilisé uniquement si l'en-tête ne contient aucun mot-clé reconnu
 *             → jamais de scan de mots-clés sur tout le texte
 *
 * Structure RIB marocain : [3 code banque][5 code ville][15 n° compte][1 clé]
 */
@Service
@Slf4j
public class BanqueDetector {

    private static final int HEADER_LINES = 40;

    // Regex BCP : "CODE BANQUE ... 145" dans l'en-tête (pattern textuel, pas RIB)
    private static final Pattern CODE_BANQUE_145_PATTERN = Pattern.compile(
            "\\bCODE\\s+BANQUE\\b.{0,40}\\b145\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // RIB marocain : 24 chiffres consécutifs — on capture les 3 premiers (code banque)
    private static final Pattern RIB_24_PATTERN = Pattern.compile("\\b(\\d{3})(\\d{21})\\b");

    // ── Table des codes banques ───────────────────────────────────────────────
    // Source : liste officielle BAM (رموز الأبناك المغربية)
    // Banque Populaire : plage 101 → 197 (gérée séparément, voir detectByRibCode)
    private static final Object[][] BANK_CODE_TABLE = {
        // code   BanqueType                    Nom affiché
        { "230",  BanqueType.CIH,              "CIH BANK"               },
        { "007",  BanqueType.ATTIJARIWAFA,     "ATTIJARIWAFA"           },
        { "011",  BanqueType.BMCE,             "BANK OF AFRICA (BMCE)"  },
        { "022",  BanqueType.SAHAM_BANK,       "SAHAM BANK"             }, // ex-SGMB, même code 022
        { "013",  BanqueType.BMCI,             "BMCI"                   },
        { "021",  BanqueType.CREDIT_DU_MAROC,  "CREDIT DU MAROC"        },
        { "225",  BanqueType.CREDIT_AGRICOLE,  "CREDIT AGRICOLE"        },
        { "350",  BanqueType.BARID_BANK,       "AL BARID BANK"          },
    };

    // Banque Populaire occupe la plage 101 → 197 (ex : 145 = BPM)
    private static final int BCP_CODE_MIN = 101;
    private static final int BCP_CODE_MAX = 197;

    // ─────────────────────────────────────────────────────────────────────────

    public BanqueDetection detect(String text) {
        if (text == null || text.isBlank()) {
            return new BanqueDetection(BanqueType.UNKNOWN, "");
        }

        // ── Préparation : en-tête seulement ──────────────────────────────────
        String header     = buildHeader(text);
        String normalized = normalize(header);      // accents supprimés + MAJUSCULES
        String compact    = compactNormalize(header); // idem + espaces/ponctuation supprimés

        // ── ÉTAPE 1 : mots-clés dans les 40 premières lignes ─────────────────

        // BCP — on cherche aussi le pattern "CODE BANQUE 145" dans l'en-tête
        if (containsAny(normalized, "BANQUE POPULAIRE", "BANQUE CENTRALE POPULAIRE", "BCP", "BANQE POPULAIRE")
                || containsAny(compact, "BANQUEPOPULAIRE", "BANQUECENTRALEPOPULAIRE", "GROUPEBANQUEPOPULAIRE", "BCP")
                || CODE_BANQUE_145_PATTERN.matcher(header).find()) {
            return new BanqueDetection(BanqueType.BCP, "BANQUE POPULAIRE");
        }

        // Quand le RIB est présent dans l'en-tête, il doit primer sur les
        // mots-clés qui peuvent apparaître dans les lignes de transactions.
        BanqueDetection ribDetection = detectByRibCode(text);
        if (ribDetection.bankType != BanqueType.UNKNOWN) {
            log.info("Banque détectée via code RIB {} : {} ({})",
                    ribDetection.bankCode, ribDetection.bankName, ribDetection.bankType);
            return ribDetection;
        }

        if (containsAny(normalized, "UMNIA", "UMNIA BANK")) {
            return new BanqueDetection(BanqueType.CIH, "UMNIA BANK");
        }
        if (containsAny(normalized, "BANK ASSAFA", "ASSAFA")) {
            return new BanqueDetection(BanqueType.ATTIJARIWAFA, "BANK ASSAFA");
        }
        if (containsAny(normalized, "BTI BANK")) {
            return new BanqueDetection(BanqueType.BMCE, "BTI BANK");
        }
        if (containsAny(normalized, "BANK AL YOUSR", "AL YOUSR")) {
            return new BanqueDetection(BanqueType.BCP, "BANK AL YOUSR");
        }
        if (containsAny(normalized, "AL AKHDAR BANK", "AL AKHDAR")) {
            return new BanqueDetection(BanqueType.CREDIT_AGRICOLE, "AL AKHDAR BANK");
        }
        if (containsAny(normalized, "NAJMAH")) {
            return new BanqueDetection(BanqueType.BMCI, "NAJMAH");
        }
        if (containsAny(normalized, "ARREDA")) {
            return new BanqueDetection(BanqueType.CREDIT_DU_MAROC, "ARREDA");
        }
        if (containsAny(normalized, "SAHAM BANK", "SAHAM")) {
            return new BanqueDetection(BanqueType.SAHAM_BANK, "SAHAM BANK");
        }
        if (containsAny(normalized, "AMERICAN EXPRESS", "AMEX")) {
            return new BanqueDetection(BanqueType.AMEX, "AMERICAN EXPRESS");
        }
        if (containsAny(normalized, "DAR AL AMANE", "DAR AL-AMANE")) {
            return new BanqueDetection(BanqueType.SOCIETE_GENERALE, "DAR AL-AMANE");
        }
        if (containsAny(normalized, "BMCI")) {
            return new BanqueDetection(BanqueType.BMCI, "BMCI");
        }
        if (containsAny(normalized, "BANK OF AFRICA", "BMCE")) {
            return new BanqueDetection(BanqueType.BMCE, "BANK OF AFRICA (BMCE)");
        }
        if (containsAny(normalized, "ATTIJARIWAFA BANK", "ATTIJARIWAFA")) {
            return new BanqueDetection(BanqueType.ATTIJARIWAFA, "ATTIJARIWAFA");
        }
        if (containsAny(normalized, "SOCIETE GENERALE", "SG MAROC", "SGMB")) {
            return new BanqueDetection(BanqueType.SOCIETE_GENERALE, "SOCIETE GENERALE");
        }
        if (containsAny(normalized, "CREDIT DU MAROC")) {
            return new BanqueDetection(BanqueType.CREDIT_DU_MAROC, "CREDIT DU MAROC");
        }
        if (containsAny(normalized, "CIH BANK", "CIH")) {
            return new BanqueDetection(BanqueType.CIH, "CIH BANK");
        }
        if (containsAny(normalized, "CREDIT AGRICOLE")) {
            return new BanqueDetection(BanqueType.CREDIT_AGRICOLE, "CREDIT AGRICOLE");
        }
        if (containsAny(normalized, "BARID BANK", "AL BARID")) {
            return new BanqueDetection(BanqueType.BARID_BANK, "AL BARID BANK");
        }

        log.warn("Banque non détectée — ni mots-clés dans l'en-tête, ni code RIB reconnu");
        return new BanqueDetection(BanqueType.UNKNOWN, "");
    }

    /**
     * Étape 2 : parcourt le texte à la recherche d'un RIB marocain (24 chiffres).
     * Extrait le code banque (3 premiers chiffres) et le mappe à une banque.
     *
     * Deux passes :
     *  1. Texte original  → RIB en chiffres contigus (ex: "007450001520500030049113")
     *  2. Texte compacté  → RIB avec espaces dans l'OCR (ex: "007 450 001520500030049113")
     *     On supprime les espaces/tabulations entre chiffres pour reconstituer le RIB.
     */
    private BanqueDetection detectByRibCode(String text) {
        // Passe 0 : chaque ligne de l'en-tête scannée individuellement (compact ligne par ligne).
        // Raison : si on joint les lignes PUIS on compacte, le numéro de page « 1 » peut fusionner
        // avec « 011 450... » pour donner « 10114500... » — la frontière de mot disparaît et le RIB
        // du compte n'est pas détecté, alors que les RIBs tiers dans les virements le sont.
        String[] allLines = text.split("\n");
        int nonEmptyCount = 0;
        for (String rawLine : allLines) {
            if (rawLine.trim().isEmpty()) continue;
            if (nonEmptyCount >= HEADER_LINES) break;
            nonEmptyCount++;
            String compactedLine = rawLine.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
            BanqueDetection result = scanForRib(compactedLine);
            if (result.bankType != BanqueType.UNKNOWN) return result;
        }

        // Passe 1 : texte entier, chiffres contigus
        BanqueDetection result = scanForRib(text);
        if (result.bankType != BanqueType.UNKNOWN) return result;

        // Passe 2 : texte entier, supprime les espaces entre chiffres
        String compacted = text.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
        return scanForRib(compacted);
    }

    private BanqueDetection scanForRib(String text) {
        Matcher m = RIB_24_PATTERN.matcher(text);
        while (m.find()) {
            String codeBanque = m.group(1);
            int    codeInt    = Integer.parseInt(codeBanque);

            if (codeInt >= BCP_CODE_MIN && codeInt <= BCP_CODE_MAX) {
                return new BanqueDetection(BanqueType.BCP, "BANQUE POPULAIRE", codeBanque);
            }
            for (Object[] entry : BANK_CODE_TABLE) {
                if (entry[0].equals(codeBanque)) {
                    return new BanqueDetection((BanqueType) entry[1], (String) entry[2], codeBanque);
                }
            }
        }
        return new BanqueDetection(BanqueType.UNKNOWN, "");
    }

    public BanqueType detectBankType(String text) {
        return detect(text).bankType;
    }

    /**
     * Identifie la banque directement à partir d'un code banque 3 chiffres (début du RIB).
     * Utilisé comme dernier fallback quand le RIB a déjà été extrait proprement ailleurs.
     */
    public BanqueDetection detectByCode(String threeDigitCode) {
        if (threeDigitCode == null || threeDigitCode.length() < 3) {
            return new BanqueDetection(BanqueType.UNKNOWN, "");
        }
        String code = threeDigitCode.substring(0, 3);
        int codeInt;
        try {
            codeInt = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return new BanqueDetection(BanqueType.UNKNOWN, "");
        }
        if (codeInt >= BCP_CODE_MIN && codeInt <= BCP_CODE_MAX) {
            return new BanqueDetection(BanqueType.BCP, "BANQUE POPULAIRE", code);
        }
        for (Object[] entry : BANK_CODE_TABLE) {
            if (entry[0].equals(code)) {
                return new BanqueDetection((BanqueType) entry[1], (String) entry[2], code);
            }
        }
        return new BanqueDetection(BanqueType.UNKNOWN, "");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildHeader(String text) {
        String[] lines  = text.split("\n");
        List<String> header = new ArrayList<>();
        for (int i = 0; i < lines.length && i < HEADER_LINES; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) header.add(line);
        }
        return String.join(" ", header);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String normalize(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFD);
        return s.replaceAll("\\p{M}", "").toUpperCase();
    }

    private String compactNormalize(String input) {
        return normalize(input).replaceAll("[^A-Z0-9]+", "");
    }

    // ── Résultat ─────────────────────────────────────────────────────────────

    public static class BanqueDetection {
        public final BanqueType bankType;
        public final String   bankName;
        public final String   bankCode; // présent seulement si détecté via code RIB (étape 2)

        public BanqueDetection(BanqueType bankType, String bankName) {
            this(bankType, bankName, null);
        }

        public BanqueDetection(BanqueType bankType, String bankName, String bankCode) {
            this.bankType = bankType;
            this.bankName = bankName;
            this.bankCode = bankCode;
        }
    }
}
