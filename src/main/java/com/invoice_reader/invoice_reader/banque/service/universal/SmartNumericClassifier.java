package com.invoice_reader.invoice_reader.banque.service.universal;
import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

import com.invoice_reader.invoice_reader.banque.service.ocr.OcrCleaningService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SmartNumericClassifier implements NumericClassifier {

    private static final Pattern DECIMAL_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}(?:[\\s\\.]\\d{3})+|\\d+)[,.]\\d{2}(?!\\d)");
    private static final Pattern EXPLODED_AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)((?:\\d\\s+){1,}\\d)\\s*([,.])\\s*((?:\\d\\s+){1,}\\d)(?!\\d)");
    private static final Pattern TRAILING_TOKEN_PATTERN = Pattern.compile("(?:^|\\s)(\\d{1,2})\\s+(\\d{1,2})\\s*$");
    private static final Pattern TRAILING_DAY_ONLY_PATTERN = Pattern.compile("(?:^|\\s)(\\d{1,2})\\s*$");
    // Reconnaît VIR.RECU, VIR RECU, VIREMENT RECU, et aussi VIREMENT INSTANTANE RECU (CIH Bank)
    // où un mot intermédiaire peut s'intercaler entre VIR(EMENT) et RECU.
    private static final Pattern VIR_RECU_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?(?:\\s*[\\./-]\\s*|\\s+(?:\\w+\\s+)?)RECU\\b");
    private static final Pattern VIREMENT_VERS_CLIENT_PATTERN = Pattern.compile(
            "\\bVIREMENT\\s*\\(?S?\\)?\\s+VERS\\s+CLIENT\\s*\\(?S?\\)?\\b");
    private static final Pattern VIR_INSTANTANE_EN_FAVEUR_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*INSTANTANE\\s+EN\\s+FAVEUR\\b");
    private static final Pattern VIR_INSTANTANE_RECU_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*INSTANTANE\\s+RECU\\b");
    private static final Pattern VIR_RTGS_RECU_PATTERN = Pattern.compile(
            "\\bVIR(?:EMENT)?\\.?\\s*RTGS\\s+RECU\\b");
    private static final Pattern VERSEMENT_CREDIT_PATTERN = Pattern.compile(
            "\\bVERSEMENT\\s+(?:PAR\\s+VOUS[-\\s]?MEME|EFFECTUE\\s+PAR)\\b");
    private static final Pattern DROIT_TIMBRE_SUR_VERSEMENT_PATTERN = Pattern.compile(
            "\\b(?:DROIT\\s+DE\\s+TIMBRE|TIMBRE)\\s+SUR\\s+VERSEMENT\\b");
    private static final Pattern FOREIGN_CARD_AMOUNT_PATTERN = Pattern.compile(
            "(?i)\\bMNT\\s*:?\\s*((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+)[,.]\\d{2})\\s*\\([A-Z]{3}\\)");
    private static final Pattern CARD_PURCHASE_DATE_BEFORE_AMOUNT_PATTERN = Pattern.compile(
            "(?i)(\\bACHAT\\s+PAR\\s+CARTE\\b.*?\\bLE\\s+)\\d{1,2}[\\/-]\\d{1,2}[\\/-]\\d{2,4}(\\s+(?=(?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+)[,.]\\d{2}\\b))");
    private static final int MIN_COLUMN_GAP = 10;
    private static final List<String> STRONG_DEBIT_HINTS = List.of(
            "OPERATION AU DEBIT", "PRELEVEMENT", "PRELEVEMENT SEPA", "RETRAIT", "PAIEMENT", "ACHAT", "CHEQUE",
            "FRAIS", "COMMISSION", "AGIOS", "COTISATION", "VIREMENT EMIS", "VIR.EMIS", "DIRECT DEBIT", "CASH OUT",
            "EMISSION D'UN VIREMENT", "EMISSION VIREMENT", "TRANSFERT CASH", "REGLEMENT D'ECHEANCE",
            // CIH / banques marocaines : "COMMISSION REMISE CHEQUE" = frais sur remise, toujours débit
            "COMMISSION REMISE");
    // Pattern pour supprimer les montants référencés "DE MAD X" du libellé (ex: FRAIS DE VIR No X DE MAD 263902,00 1072,50)
    // afin que seul le montant transactionnel réel (le dernier) soit extrait.
    private static final Pattern DE_MAD_REF_PATTERN = Pattern.compile(
            "(?i)\\bDE\\s+MAD\\s+((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+)[,.]\\d{2})(?=\\s+(?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+)[,.]\\d{2})");
    private static final Pattern SHORT_REF_BEFORE_AMOUNT_PATTERN = Pattern.compile(
            "(?i)\\bREF\\s+\\d{1,4}\\s+(?=(?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+)[,.]\\d{2}\\b)");
    private static final List<String> STRONG_CREDIT_HINTS = List.of(
            "VIREMENT RECU", "VIR RECU", "VIR.RECU", "VIREMENT INSTANTANE RECU", "CREDIT VIREMENT",
            "VIR RTGS RECU", "VIR. RTGS RECU", "VIREMENT RTGS RECU",
            "VERSEMENT", "REMISE CHEQUE",
            "REMISE", "ENCAISSEMENT", "REMBOURSEMENT", "SALAIRE", "PAYROLL", "SALARY", "REFUND", "CASH IN",
            "VENTE PAR CARTE", "VENTE CARTE", "RECEPTION D'UN VIREMENT", "RECEPTION VIREMENT DE");
    private final OcrCleaningService cleaningService;
    private final BanqueLayoutProfileRegistry profileRegistry;

    public SmartNumericClassifier(OcrCleaningService cleaningService, BanqueLayoutProfileRegistry profileRegistry) {
        this.cleaningService = cleaningService;
        this.profileRegistry = profileRegistry;
    }

    @Override
    public NumericClassification classify(List<String> blockLines, String description, TransactionExtractionContext context) {
        List<String> flags = new ArrayList<>();
        List<Candidate> candidates = extractDecimalCandidates(blockLines);
        if (candidates.isEmpty()) {
            flags.add("NO_AMOUNT");
            return new NumericClassification(BigDecimal.ZERO, BigDecimal.ZERO, null, flags);
        }

        candidates.sort(Comparator.comparingInt(Candidate::lineIndex).thenComparingInt(Candidate::start));
        Candidate balanceCandidate = findBalanceCandidate(blockLines, candidates);

        List<Candidate> core = new ArrayList<>(candidates);
        if (balanceCandidate != null) {
            core.remove(balanceCandidate);
        }

        if (core.isEmpty()) {
            flags.add("ONLY_BALANCE_CANDIDATE");
            return new NumericClassification(BigDecimal.ZERO, BigDecimal.ZERO,
                    balanceCandidate != null ? balanceCandidate.value() : null, flags);
        }

        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        boolean likelyCredit = hasCreditHint(description, context);
        boolean likelyDebit = hasDebitHint(description, context);

        if (core.size() >= 2) {
            Candidate c1 = core.get(core.size() - 2);
            Candidate c2 = core.get(core.size() - 1);
            boolean separatedColumns = isColumnSeparated(c1, c2);
            if (separatedColumns) {
                if (c1.start() <= c2.start()) {
                    debit = c1.value();
                    credit = c2.value();
                } else {
                    debit = c2.value();
                    credit = c1.value();
                }
                flags.add("INDEX_BASED_ASSIGNMENT");
            } else {
                debit = c1.value();
                credit = c2.value();
                // Si deux montants sont présents sur une même transaction,
                // on conserve explicitement les deux (debit + credit).
                flags.add("DUAL_AMOUNT_PRESERVED");
            }
        } else {
            BigDecimal single = core.get(0).value();
            AmountDirection direction = inferDirection(description, context);
            if (direction == AmountDirection.CREDIT || (likelyCredit && !likelyDebit)) {
                credit = single;
            } else if (direction == AmountDirection.DEBIT || (likelyDebit && likelyCredit)) {
                debit = single;
            } else {
                // Direction INCONNUE : pour Saham Bank (layout deux-colonnes Débit|Crédit), utiliser
                // la position OCR pour distinguer la colonne.
                // Pour toutes les autres banques (layout une-colonne) → défaut DÉBIT.
                boolean isSahamTwoColumn = context.bankType() == com.invoice_reader.invoice_reader.banque.entity.BanqueType.SAHAM_BANK
                        || context.twoColumnAmountLayout();
                if (isSahamTwoColumn && core.get(0).atEndOfLine()) {
                    credit = single;
                    flags.add("CREDIT_BY_COLUMN_POSITION");
                } else {
                    debit = single;
                    flags.add("DEBIT_BY_DEFAULT_UNKNOWN");
                }
            }
            flags.add("SINGLE_AMOUNT");
        }

        if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
            flags.add("DUAL_AMOUNT");
        }

        return new NumericClassification(debit, credit, balanceCandidate != null ? balanceCandidate.value() : null, flags);
    }

    private boolean isColumnSeparated(Candidate c1, Candidate c2) {
        return Math.abs(c2.start() - c1.start()) >= MIN_COLUMN_GAP;
    }

    private List<Candidate> extractDecimalCandidates(List<String> blockLines) {
        List<Candidate> values = new ArrayList<>();
        for (int i = 0; i < blockLines.size(); i++) {
            String line = sanitizeLineForAmountExtraction(blockLines.get(i));
            Matcher matcher = DECIMAL_PATTERN.matcher(line);
            while (matcher.find()) {
                String raw = matcher.group();
                // Colonne Crédit (fin réelle de ligne) : peu ou pas de contenu après le montant (≤3 chars).
                // Colonne Débit (colonne Crédit vide) : montant suivi de nombreux espaces (≥8 chars blancs).
                // Cette distinction n'est utilisée que si twoColumnAmountLayout est vrai dans le contexte.
                String tail = matcher.end() < line.length() ? line.substring(matcher.end()) : "";
                boolean atEndOfLine = tail.length() <= 3;
                if (looksLikeDateAmountMerge(line, matcher.start(), raw)) {
                    BigDecimal fixed = parseAmountFromMergedDateAmount(raw);
                    if (fixed.compareTo(BigDecimal.ZERO) > 0) {
                        values.add(new Candidate(i, matcher.start(), raw, fixed, atEndOfLine));
                    }
                    continue;
                }
                BigDecimal value = parseAmount(raw);
                if (value.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                values.add(new Candidate(i, matcher.start(), raw, value, atEndOfLine));
            }
        }
        return values;
    }

    private Candidate findBalanceCandidate(List<String> blockLines, List<Candidate> candidates) {
        for (int i = 0; i < blockLines.size(); i++) {
            String upper = blockLines.get(i).toUpperCase();
            if (!upper.contains("SOLDE")) {
                continue;
            }
            Candidate best = null;
            for (Candidate c : candidates) {
                if (c.lineIndex() == i) {
                    if (best == null || c.start() > best.start()) {
                        best = c;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        if (candidates.size() >= 3) {
            return candidates.get(candidates.size() - 1);
        }
        if (candidates.size() == 2) {
            Candidate left = candidates.get(0);
            Candidate right = candidates.get(1);
            if (right.value().compareTo(left.value()) > 0 && right.value().compareTo(left.value().multiply(new BigDecimal("2"))) >= 0) {
                return right;
            }
        }
        return null;
    }

    private boolean hasCreditHint(String description, TransactionExtractionContext context) {
        String upper = description == null ? "" : description.toUpperCase();
        String normalizedUpper = normalizeHintText(upper);
        BanqueLayoutProfile profile = profileRegistry.getProfile(context.bankType());
        for (String h : profile.creditHints()) {
            if (containsHint(upper, normalizedUpper, h)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDebitHint(String description, TransactionExtractionContext context) {
        String upper = description == null ? "" : description.toUpperCase();
        String normalizedUpper = normalizeHintText(upper);
        BanqueLayoutProfile profile = profileRegistry.getProfile(context.bankType());
        for (String h : profile.debitHints()) {
            if (containsHint(upper, normalizedUpper, h)) {
                return true;
            }
        }
        return false;
    }

    private AmountDirection inferDirection(String description, TransactionExtractionContext context) {
        String upper = description == null ? "" : description.toUpperCase();
        String normalizedUpper = normalizeHintText(upper);
        BanqueLayoutProfile profile = profileRegistry.getProfile(context.bankType());
        int debitScore = 0;
        int creditScore = 0;

        for (String h : profile.debitHints()) {
            if (containsHint(upper, normalizedUpper, h)) {
                debitScore++;
            }
        }
        for (String h : profile.creditHints()) {
            if (containsHint(upper, normalizedUpper, h)) {
                creditScore++;
            }
        }
        for (String h : STRONG_DEBIT_HINTS) {
            if (containsHint(upper, normalizedUpper, h)) {
                debitScore += 2;
            }
        }
        for (String h : STRONG_CREDIT_HINTS) {
            if (containsHint(upper, normalizedUpper, h)) {
                creditScore += 2;
            }
        }

        if (containsHint(upper, normalizedUpper, "FRAIS TIMBRE")
                || containsHint(upper, normalizedUpper, "COMMISSION")) {
            debitScore += 2;
        }
        // "FRAIS SUR REMISE ..." / "FRAIS REMISE ..." = frais bancaires facturés sur une remise de chèque.
        // Même si la description contient "REMISE CHEQUE" (hint crédit), le préfixe "FRAIS" indique
        // une charge débitée au client. On force DÉBIT avec un score élevé.
        if (containsHint(upper, normalizedUpper, "FRAIS SUR REMISE")
                || containsHint(upper, normalizedUpper, "FRAIS REMISE")) {
            debitScore += 6;
        }
        if (containsHint(upper, normalizedUpper, "VIREMENT EMIS")
                || containsHint(upper, normalizedUpper, "VIR.EMIS")
                || containsHint(upper, normalizedUpper, "DIRECT DEBIT")) {
            debitScore += 3;
        }
        if (containsHint(upper, normalizedUpper, "REMISE CHEQUE")
                || containsHint(upper, normalizedUpper, "VIREMENT RECU")
                || containsHint(upper, normalizedUpper, "VIR.RECU")) {
            creditScore += 3;
        }
        if (containsHint(upper, normalizedUpper, "PAYROLL")
                || containsHint(upper, normalizedUpper, "SALARY")
                || containsHint(upper, normalizedUpper, "REFUND")) {
            creditScore += 3;
        }
        if (VIR_RECU_PATTERN.matcher(upper).find()) {
            creditScore += 4;
        }
        if (VIR_INSTANTANE_RECU_PATTERN.matcher(upper).find()) {
            creditScore += 5;
        }
        if (VIR_RTGS_RECU_PATTERN.matcher(upper).find()) {
            creditScore += 5;
        }
        if (VIREMENT_VERS_CLIENT_PATTERN.matcher(upper).find()) {
            debitScore += 5;
        }
        if (VIR_INSTANTANE_EN_FAVEUR_PATTERN.matcher(upper).find()) {
            debitScore += 5;
        }
        if (VERSEMENT_CREDIT_PATTERN.matcher(upper).find()) {
            creditScore += 5;
        }
        if (DROIT_TIMBRE_SUR_VERSEMENT_PATTERN.matcher(upper).find()) {
            debitScore += 6;
        }

        if (debitScore > creditScore) {
            return AmountDirection.DEBIT;
        }
        if (creditScore > debitScore) {
            return AmountDirection.CREDIT;
        }
        return AmountDirection.UNKNOWN;
    }

    private boolean containsHint(String rawUpper, String normalizedRawUpper, String hint) {
        if (rawUpper == null || hint == null || hint.isBlank()) {
            return false;
        }
        if (rawUpper.contains(hint)) {
            return true;
        }
        String normalizedHint = normalizeHintText(hint);
        if (normalizedHint.isBlank()) {
            return false;
        }
        return normalizedRawUpper.contains(normalizedHint);
    }

    private String normalizeHintText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[^A-Z0-9]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String sanitizeLineForAmountExtraction(String line) {
        if (line == null) {
            return "";
        }
        // Cas "FRAIS DE VIREMENT No X DE MAD 263902,00 1072,50": retire le montant référencé
        // "DE MAD 263902,00" pour ne garder que le vrai montant transactionnel (1072,50).
        // Le lookahead garantit qu'on ne retire que si un 2e montant suit (sinon l'unique montant est le frais).
        String sanitized = DE_MAD_REF_PATTERN.matcher(line).replaceAll("DE MAD ");
        // Cas BCP / GLOVO : "REF 9 261,90" où le chiffre "9" appartient à la référence
        // et non au montant. Sans cette correction, l'extraction lit à tort "9 261,90".
        sanitized = SHORT_REF_BEFORE_AMOUNT_PATTERN.matcher(sanitized).replaceAll("REF ");
        // Achats carte en devise: "MNT: 182,66(EUR)" est un montant descriptif, pas une colonne débit/crédit.
        sanitized = FOREIGN_CARD_AMOUNT_PATTERN.matcher(sanitized).replaceAll("MNT: ");
        // Achats carte BCP: "... LE 06/11/25 640,79" peut être lu à tort comme "25 640,79".
        // On retire la date descriptive après "LE" quand un vrai montant comptable suit immédiatement.
        sanitized = CARD_PURCHASE_DATE_BEFORE_AMOUNT_PATTERN.matcher(sanitized).replaceAll("$1$2");
        // Evite la fusion "CHEQUE 458 150,00" => 458150,00
        sanitized = sanitized.replaceAll(
                "(?i)\\bCHEQUE\\s+\\d{1,8}\\s+(?=\\d{1,3}(?:[\\s\\.]\\d{3})*[,.]\\d{2}\\b)",
                "CHEQUE ");
        // Corrige des montants OCR deformes: "49.,44" -> "49,44"
        sanitized = sanitized.replaceAll(
                "(?<!\\d)((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+))[\\.,]\\s*[\\.,](\\d{2})(?!\\d)",
                "$1,$2");
        // Corrige des montants OCR avec 1 decimal: "2,0" -> "2,00"
        sanitized = sanitized.replaceAll(
                "(?<!\\d)((?:\\d{1,3}(?:[\\s\\.]\\d{3})*|\\d+))[\\.,](\\d)(?!\\d)",
                "$1,$20");
        return normalizeExplodedAmounts(sanitized);
    }

    private String normalizeExplodedAmounts(String line) {
        Matcher matcher = EXPLODED_AMOUNT_PATTERN.matcher(line);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String integerPart = matcher.group(1).replaceAll("\\s+", "");
            String separator = matcher.group(2);
            String decimalPart = matcher.group(3).replaceAll("\\s+", "");
            integerPart = sanitizeExplodedIntegerPart(integerPart);
            // Garde-fou: evite de convertir des sequences OCR longues (dates+references)
            // en montants geants.
            if (integerPart == null || integerPart.isBlank() || decimalPart.length() < 1 || decimalPart.length() > 2) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (decimalPart.length() > 2) {
                decimalPart = decimalPart.substring(0, 2);
            } else if (decimalPart.length() == 1) {
                decimalPart = decimalPart + "0";
            }
            String replacement = integerPart + separator + decimalPart;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String sanitizeExplodedIntegerPart(String integerPart) {
        if (integerPart == null || integerPart.isBlank()) {
            return null;
        }
        if (integerPart.length() <= 7) {
            return integerPart;
        }
        // Cas OCR fréquent: date collée devant le montant (ex: 191220251200).
        // Si on détecte DDMMYYYY juste avant la fin, on conserve uniquement la queue montant.
        for (int amountLen = 5; amountLen >= 1; amountLen--) {
            if (integerPart.length() <= amountLen) {
                continue;
            }
            int split = integerPart.length() - amountLen;
            if (split < 8) {
                continue;
            }
            String dateToken = integerPart.substring(split - 8, split);
            if (looksLikeCompactDate(dateToken)) {
                return integerPart.substring(split);
            }
        }
        return null;
    }

    private boolean looksLikeCompactDate(String token) {
        if (token == null || !token.matches("\\d{8}")) {
            return false;
        }
        int day = Integer.parseInt(token.substring(0, 2));
        int month = Integer.parseInt(token.substring(2, 4));
        int year = Integer.parseInt(token.substring(4, 8));
        return day >= 1 && day <= 31 && month >= 1 && month <= 12 && year >= 1900 && year <= 2100;
    }

    private boolean looksLikeDateAmountMerge(String line, int startIndex, String rawCandidate) {
        // Cas fréquent OCR: "... 01 12                108,90" où "12 ... 108,90" est détecté à tort.
        if (rawCandidate == null) {
            return false;
        }
        String compact = rawCandidate.trim().replaceAll("\\s+", " ");
        // Pattern étendu pour gérer aussi les montants en milliers ("03 142 000,00")
        // et pas seulement les montants < 1000 ("03 415,00").
        if (!compact.matches("\\d{1,2}(?:\\s\\d{3})+[,.]\\d{2}")) {
            return false;
        }
        String[] chunks = compact.split(" ");
        if (chunks.length < 2) {
            return false;
        }
        String rightNumeric = chunks[1].replace(',', '.');
        int dot = rightNumeric.indexOf('.');
        String rightIntegerPart = dot >= 0 ? rightNumeric.substring(0, dot) : rightNumeric;
        if ("000".equals(rightIntegerPart)) {
            // "12 000,00" est généralement un vrai montant (12 000,00), pas une fusion date/montant.
            return false;
        }
        int left;
        try {
            left = Integer.parseInt(chunks[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        // Si le premier groupe n'est pas un mois plausible, ce n'est pas une fusion date/montant.
        if (left < 1 || left > 12) {
            return false;
        }
        if (startIndex <= 0) {
            return false;
        }
        String prefix = line.substring(0, startIndex);
        Matcher matcher = TRAILING_TOKEN_PATTERN.matcher(prefix);
        if (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                if (day < 1 || day > 31 || month < 1 || month > 12 || month != left) {
                    return false;
                }
                // Règle de proximité pour distinguer une vraie fusion OCR ("03 108,90")
                // d'un séparateur de milliers ("3 415,00" = 3 415 DH) :
                //
                // • Candidat zero-paddé ("02", "03"...) : un montant comptable ne commence
                //   jamais par un zéro. "03 415,00" est TOUJOURS une fusion date/montant.
                //   → on utilise spacesAfterMonth comme critère de confirmation.
                //
                // • Candidat non-zero-paddé ("3", "5"...) : après normalisation des espaces
                //   (le bloc builder collapse N espaces en 1), spacesAfterMonth vaut toujours 1,
                //   rendant le seuil de 8 inutilisable. Un "3" isolé précédant "415,00" est
                //   vraisemblablement le chiffre des milliers d'un montant de 3 415 DH.
                //   → on ne fusionne pas (return false) pour éviter de tronquer le montant.
                boolean zeropadded = chunks[0].startsWith("0");
                if (!zeropadded) {
                    return false;
                }
                int spacesAfterMonth = prefix.length() - matcher.end(2);
                return spacesAfterMonth < 8;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        Matcher dayOnly = TRAILING_DAY_ONLY_PATTERN.matcher(prefix);
        if (dayOnly.find()) {
            // Les mois zero-paddés ("02", "03"...) ne peuvent jamais être le chiffre
            // de tête d'un vrai montant : en comptabilité française, aucun montant
            // n'est écrit "02 108,90". C'est forcément "février" devant "108,90".
            // Pour les mois non-zero-paddés à un chiffre (ex. "2"), on garde la
            // restriction existante pour éviter les faux positifs ("2 108,90" = 2108.90 légitime).
            boolean zeropadded = chunks[0].startsWith("0");
            if (left < 10 && !zeropadded) {
                return false;
            }
            try {
                int day = Integer.parseInt(dayOnly.group(1));
                return day >= 1 && day <= 31;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private BigDecimal parseAmountFromMergedDateAmount(String rawCandidate) {
        int firstSpace = rawCandidate.indexOf(' ');
        if (firstSpace < 0 || firstSpace + 1 >= rawCandidate.length()) {
            return BigDecimal.ZERO;
        }
        String rightPart = rawCandidate.substring(firstSpace + 1).trim();
        return parseAmount(rightPart);
    }

    private BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(cleaningService.normalizeAmount(raw));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * @param atEndOfLine true si le montant est en fin de ligne (colonne Crédit du relevé bancaire),
     *                    false si suivi d'espaces/contenu (colonne Débit, crédit vide).
     */
    private record Candidate(int lineIndex, int start, String raw, BigDecimal value, boolean atEndOfLine) {
    }

    private enum AmountDirection {
        DEBIT,
        CREDIT,
        UNKNOWN
    }
}
