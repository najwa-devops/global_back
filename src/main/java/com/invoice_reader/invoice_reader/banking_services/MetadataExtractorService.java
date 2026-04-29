package com.invoice_reader.invoice_reader.banking_services;

import com.invoice_reader.invoice_reader.banking_services.banking_ocr.OcrCleaningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataExtractorService {

    private final OcrCleaningService cleaningService;
    private final BankDetector bankDetector;
    private final PrimaryRibExtractor primaryRibExtractor;
    private final StatementPeriodExtractor periodExtractor;
    private final StatementTotalsExtractor totalsExtractor;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2})[/\\-\\s](\\d{2})[/\\-\\s](\\d{2,4})");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("[-]?\\d+(?:(?:\\s|\\.)\\d{3})*(?:[,.]\\d{2})");
    private static final BigDecimal MAX_DECIMAL_15_2 = new BigDecimal("9999999999999.99");

    private static final List<String> OPENING_BALANCE_KEYWORDS = Arrays.asList(
            "SOLDE AU", "SOLDE DEPART", "SOLDE PRECEDENT", "ANCIEN SOLDE", "SOLDE INITIAL", "VOTRE SOLDE AU");

    private static final List<String> CLOSING_BALANCE_KEYWORDS = Arrays.asList(
            "SOLDE FINAL", "NOUVEAU SOLDE", "SOLDE ACTUEL", "SOLDE EN FIN DE PERIODE");

    public BankStatementMetadata extractMetadata(String ocrText) {
        log.info("Extracting statement metadata");

        BankStatementMetadata metadata = new BankStatementMetadata();

        if (ocrText == null || ocrText.isEmpty()) {
            log.warn("Empty OCR text");
            return metadata;
        }

        BankDetector.BankDetection detection = bankDetector.detect(ocrText);
        metadata.bankType = detection.bankType;
        metadata.bankName = detection.bankName;

        // AMEX documents (SUBMISSION DETAILS) contain ARNs and approval codes that can
        // accidentally form 24-digit sequences — they never contain Moroccan RIBs
        if (metadata.bankType != BankType.AMEX) {
            metadata.rib = primaryRibExtractor.extractPrimaryRib(ocrText);
        }

        // Fallback : si la banque est encore inconnue mais qu'on a un RIB propre,
        // on identifie la banque via les 3 premiers chiffres du RIB.
        // Cela couvre les cas où le RIB apparaît avec des espaces dans le PDF
        // et n'est donc pas capturé par le regex 24-chiffres du BankDetector.
        if (metadata.bankType == BankType.UNKNOWN
                && metadata.rib != null
                && metadata.rib.replaceAll("\\D", "").length() >= 3) {
            String digitsOnly = metadata.rib.replaceAll("\\D", "");
            BankDetector.BankDetection ribCodeDetection = bankDetector.detectByCode(digitsOnly.substring(0, 3));
            if (ribCodeDetection.bankType != BankType.UNKNOWN) {
                log.info("Banque résolue via code RIB extrait ({}) : {}",
                        digitsOnly.substring(0, 3), ribCodeDetection.bankName);
                metadata.bankType = ribCodeDetection.bankType;
                metadata.bankName = ribCodeDetection.bankName;
            }
        }

        StatementPeriodExtractor.StatementPeriod period = periodExtractor.extractPeriod(ocrText);
        if (period != null) {
            metadata.month = period.month;
            metadata.year = period.year;
            metadata.startDate = period.startDate;
            metadata.endDate = period.endDate;
        }

        StatementTotalsExtractor.Totals totals = totalsExtractor.extractTotals(ocrText);
        metadata.totalDebitPdf = totals.totalDebitPdf;
        metadata.totalCreditPdf = totals.totalCreditPdf;

        metadata.openingBalance = extractOpeningBalance(ocrText);
        metadata.closingBalance = extractClosingBalance(ocrText);
        metadata.accountHolder = extractAccountHolder(ocrText);

        return metadata;
    }

    private BigDecimal extractOpeningBalance(String text) {
        return extractBalanceByKeywords(text, OPENING_BALANCE_KEYWORDS, false);
    }

    private BigDecimal extractClosingBalance(String text) {
        return extractBalanceByKeywords(text, CLOSING_BALANCE_KEYWORDS, true);
    }

    private BigDecimal extractBalanceByKeywords(String text, List<String> keywords, boolean preferLastMatch) {
        String upperText = text.toUpperCase();
        BigDecimal selected = null;

        for (String keyword : keywords) {
            int index = upperText.indexOf(keyword);
            while (index != -1) {
                List<BigDecimal> amounts = extractAmountsForBalance(text, index);
                if (amounts.isEmpty()) {
                    index = upperText.indexOf(keyword, index + keyword.length());
                    continue;
                }

                selected = preferLastMatch ? amounts.get(amounts.size() - 1) : amounts.get(0);
                if (!preferLastMatch) {
                    return selected;
                }

                index = upperText.indexOf(keyword, index + keyword.length());
            }
        }

        return selected;
    }

    private List<BigDecimal> extractAmountsForBalance(String text, int keywordIndex) {
        int lineStart = Math.max(0, text.lastIndexOf('\n', keywordIndex));
        int firstLineEnd = text.indexOf('\n', keywordIndex);
        int lineEnd = firstLineEnd == -1 ? text.length() : firstLineEnd;

        String currentLine = text.substring(lineStart, lineEnd);
        List<BigDecimal> currentLineAmounts = extractAmountsFromBalanceLine(currentLine);
        if (!currentLineAmounts.isEmpty()) {
            return currentLineAmounts;
        }

        if (firstLineEnd == -1) {
            return List.of();
        }

        // Some banks place the balance on the line immediately following the keyword.
        int secondLineEnd = text.indexOf('\n', firstLineEnd + 1);
        int fallbackEnd = secondLineEnd == -1 ? text.length() : secondLineEnd;
        String fallbackArea = text.substring(lineStart, fallbackEnd);
        return extractAmountsFromBalanceLine(fallbackArea);
    }

    private List<BigDecimal> extractAmountsFromBalanceLine(String candidateText) {
        String cleanArea = DATE_PATTERN.matcher(candidateText).replaceAll(" [DATE] ");
        Matcher matcher = AMOUNT_PATTERN.matcher(cleanArea);
        List<BigDecimal> amounts = new java.util.ArrayList<>();

        while (matcher.find()) {
            String match = matcher.group();
            String amountStr = cleaningService.normalizeAmount(match);
            try {
                BigDecimal amount = new BigDecimal(amountStr);
                if (amount.compareTo(BigDecimal.ZERO) >= 0
                        && amount.compareTo(MAX_DECIMAL_15_2) <= 0) {
                    amounts.add(amount);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return amounts;
    }

    private String extractAccountHolder(String text) {
        Pattern holderPattern = Pattern.compile(
                "(?:TITULAIRE|COMPTE DE|M\\.|MME|MR)\\s*:?\\s*([A-Z][A-Z\\s]+)",
                Pattern.CASE_INSENSITIVE);

        Matcher matcher = holderPattern.matcher(text);

        if (matcher.find()) {
            String holder = matcher.group(1).trim();
            if (holder.length() > 100) {
                holder = holder.substring(0, 100);
            }
            return holder;
        }

        return null;
    }

    public static class BankStatementMetadata {
        public String rib;
        public Integer month;
        public Integer year;
        public BigDecimal openingBalance;
        public BigDecimal closingBalance;
        public String bankName;
        public String accountHolder;
        public LocalDate startDate;
        public LocalDate endDate;
        public BigDecimal totalDebitPdf;
        public BigDecimal totalCreditPdf;
        public BankType bankType;
    }
}
