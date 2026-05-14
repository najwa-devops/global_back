package com.invoice_reader.invoice_reader.banque.centremonetique.service;

import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.banque.centremonetique.dto.CentreMonetiqueBatchDetailDTO;
import com.invoice_reader.invoice_reader.banque.centremonetique.dto.CentreMonetiqueBatchSummaryDTO;
import com.invoice_reader.invoice_reader.banque.centremonetique.dto.CentreMonetiqueExtractionRow;
import com.invoice_reader.invoice_reader.banque.centremonetique.entity.CentreMonetiqueBatch;
import com.invoice_reader.invoice_reader.banque.centremonetique.entity.CentreMonetiqueTransaction;
import com.invoice_reader.invoice_reader.banque.centremonetique.repository.CentreMonetiqueBatchRepository;
import com.invoice_reader.invoice_reader.banque.centremonetique.repository.CentreMonetiqueBatchSummaryProjection;
import com.invoice_reader.invoice_reader.banque.centremonetique.repository.CentreMonetiqueTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class CentreMonetiqueWorkflowService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Extrait le numéro TPE depuis un libellé bancaire "VENTE PAR CARTE  000285". */
    private static final Pattern BANK_TPE_PATTERN = Pattern.compile(
            "VENTE\\s+PAR\\s+CARTE\\s+([A-Z0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extrait le code TPE depuis les derniers chiffres d'un libellé bancaire.
     * Couvre : "AZAR RESTAURAN294055", "ATTEATUDE CAF000294", "AZAR REST MARRAKEC000028".
     * Le TPE est toujours les 5 ou 6 derniers chiffres du libellé.
     */
    private static final Pattern BANK_TPE_TRAILING_PATTERN = Pattern.compile(
            "([0-9]{5,6})\\s*$");

    /** Extrait le code commerçant depuis un libellé bancaire "... ACQ86097 ...". */
    private static final Pattern BANK_ACQ_PATTERN = Pattern.compile(
            "ACQ([0-9]{4,10})\\b",
            Pattern.CASE_INSENSITIVE);

    /** Extrait le code commerçant depuis le texte OCR BARID_BANK "COMMERCANT : 86097". */
    private static final Pattern BARID_COMMERCANT_CODE_PATTERN = Pattern.compile(
            "(?i)COMMERCANT\\s*[:\\-]?\\s*(?:[^\\d\\n]{0,40})?([0-9]{4,10})");
    private static final Pattern AMEX_IBAN_LAST5_PATTERN = Pattern.compile(
            "(?i)LAST\\s*[5S]\\s*DIGITS\\s*OF\\s*I[B8]AN\\s*[:\\-]?\\s*([0-9]{5})");

    private final CentreMonetiqueBatchRepository batchRepository;
    private final CentreMonetiqueTransactionRepository transactionRepository;
    private final CentreMonetiqueExtractionService extractionService;
    private final BanqueReleveRepository bankStatementRepository;

    @Transactional
    public CentreMonetiqueBatchDetailDTO uploadAndExtract(MultipartFile file,
                                                          Integer year,
                                                          CentreMonetiqueStructureType structureType,
                                                          String rib, Long resolvedDossierId) throws Exception {
        CentreMonetiqueBatch batch = new CentreMonetiqueBatch();
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document";
        String safeOriginalName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

        batch.setFilename(UUID.randomUUID() + "_" + safeOriginalName);
        batch.setOriginalName(originalName);
        batch.setFileContentType(file.getContentType());
        batch.setFileSize(file.getSize());
        batch.setFileData(file.getBytes());
        batch.setStatus("PROCESSING");
        batch.setStructure((structureType != null ? structureType : CentreMonetiqueStructureType.AUTO).name());
        batch.setDossierId(resolvedDossierId);
        if (rib != null && !rib.isBlank()) {
            batch.setRib(normalizeRibDigits(rib));
        }
        batch = batchRepository.save(batch);

        try {
            CentreMonetiqueExtractionService.ExtractionPayload payload = extractionService.extract(
                    batch.getFileData(),
                    batch.getOriginalName(),
                    year,
                    structureType);
            List<CentreMonetiqueExtractionRow> rows = payload.rows();
            batch.setRawOcrText(payload.rawOcrText());
            batch.setStructure(payload.detectedStructure() != null && !payload.detectedStructure().isBlank()
                    ? payload.detectedStructure()
                    : CentreMonetiqueStructureType.AUTO.name());
            if (CentreMonetiqueStructureType.AMEX.name().equals(batch.getStructure())) {
                if (batch.getRib() == null || batch.getRib().isBlank()) {
                    Optional<String> amexRib = resolveAmexRibFromLast5(payload.rawOcrText());
                    if (amexRib.isPresent()) {
                        batch.setRib(normalizeRibDigits(amexRib.get()));
                    }
                }
            } else if ((batch.getRib() == null || batch.getRib().isBlank()) && payload.extractedRib() != null && !payload.extractedRib().isBlank()) {
                batch.setRib(normalizeRibDigits(payload.extractedRib()));
            }
            persistRows(batch, rows, payload.summaryTotals());
            batch.setStatus("PROCESSED");
            batch = batchRepository.save(batch);
            return toDetailDTO(batch, rows, true);
        } catch (Exception e) {
            batch.setStatus("ERROR");
            batch.setErrorMessage(limitError(e.getMessage()));
            batch = batchRepository.save(batch);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<CentreMonetiqueBatchDetailDTO> findDetail(Long id, boolean includeRawOcr, Long resolvedDossierId) {
        return findBatchForDossier(id, resolvedDossierId)
                .map(batch -> {
                    List<CentreMonetiqueExtractionRow> rows = toRows(transactionRepository.findByBatchIdOrderByRowIndexAsc(batch.getId()));
                    return toDetailDTO(batch, rows, includeRawOcr);
                });
    }

    @Transactional(readOnly = true)
    public List<CentreMonetiqueBatchSummaryDTO> list(int limit, Long resolvedDossierId) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return batchRepository.findTop200ByDossierIdOrDossierIdIsNullOrderByCreatedAtDesc(resolvedDossierId).stream()
                .limit(safeLimit)
                .map(this::toSummaryDTO)
                .toList();
    }

    /** Met à jour uniquement le RIB d'un batch existant. */
    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> updateRib(Long id, String rib, Long resolvedDossierId) {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(id, resolvedDossierId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        batch.setRib(normalizeRibDigits(rib));
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        List<CentreMonetiqueExtractionRow> rows = toRows(transactionRepository.findByBatchIdOrderByRowIndexAsc(saved.getId()));
        return Optional.of(toDetailDTO(saved, rows, false));
    }

    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> reprocess(Long id,
                                                             Integer year,
                                                             CentreMonetiqueStructureType structureType, Long resolvedDossierId) throws Exception {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(id, resolvedDossierId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }

        CentreMonetiqueBatch batch = optional.get();
        if (batch.getFileData() == null || batch.getFileData().length == 0) {
            throw new IllegalStateException("Fichier source introuvable pour retraitement");
        }

        batch.setStatus("PROCESSING");
        batch.setErrorMessage(null);
        transactionRepository.deleteByBatchId(batch.getId());

        CentreMonetiqueStructureType effectiveStructure = structureType != null
                ? structureType
                : CentreMonetiqueStructureType.fromNullable(batch.getStructure());
        batch.setStructure(effectiveStructure.name());

        CentreMonetiqueExtractionService.ExtractionPayload payload = extractionService.extract(
                batch.getFileData(),
                batch.getOriginalName(),
                year,
                effectiveStructure);
        List<CentreMonetiqueExtractionRow> rows = payload.rows();
        batch.setRawOcrText(payload.rawOcrText());
        String resolvedStructure = payload.detectedStructure() != null && !payload.detectedStructure().isBlank()
                ? payload.detectedStructure()
                : CentreMonetiqueStructureType.AUTO.name();
        batch.setStructure(resolvedStructure);
        if (CentreMonetiqueStructureType.AMEX.name().equals(resolvedStructure)) {
            if (batch.getRib() == null || batch.getRib().isBlank()) {
                resolveAmexRibFromLast5(payload.rawOcrText()).ifPresent(value -> batch.setRib(normalizeRibDigits(value)));
            }
        } else if ((batch.getRib() == null || batch.getRib().isBlank()) && payload.extractedRib() != null && !payload.extractedRib().isBlank()) {
            batch.setRib(normalizeRibDigits(payload.extractedRib()));
        }
        persistRows(batch, rows, payload.summaryTotals());

        batch.setStatus("PROCESSED");
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        return Optional.of(toDetailDTO(saved, rows, true));
    }

    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> saveRows(Long id, List<CentreMonetiqueExtractionRow> rows, Long resolvedDossierId) {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(id, resolvedDossierId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        transactionRepository.deleteByBatchId(batch.getId());
        List<CentreMonetiqueExtractionRow> safeRows = rows != null ? rows : List.of();
        persistRows(batch, safeRows, null);
        batch.setStatus("PROCESSED");
        CentreMonetiqueBatch saved = batchRepository.save(batch);
        return Optional.of(toDetailDTO(saved, safeRows, true));
    }


    @Transactional
    public boolean delete(Long id, Long resolvedDossierId) {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(id, resolvedDossierId);
        if (optional.isEmpty()) {
            return false;
        }
        batchRepository.delete(optional.get());
        return true;
    }

    @Transactional
    public Optional<CentreMonetiqueBatchDetailDTO> clientValidate(Long id, Long resolvedDossierId, String userId) {
        Optional<CentreMonetiqueBatch> optional = findBatchForDossier(id, resolvedDossierId);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        CentreMonetiqueBatch batch = optional.get();
        if (!batch.isClientValidated()) {
            batch.clientValidate(userId != null && !userId.isBlank() ? userId : "client");
            batchRepository.save(batch);
        }
        List<CentreMonetiqueExtractionRow> rows = toRows(transactionRepository.findByBatchIdOrderByRowIndexAsc(batch.getId()));
        return Optional.of(toDetailDTO(batch, rows, true));
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> filePayload(Long id, Long resolvedDossierId) {
        return findBatchForDossier(id, resolvedDossierId)
                .map(batch -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("filename", batch.getFilename());
                    payload.put("contentType",
                            batch.getFileContentType() != null ? batch.getFileContentType() : "application/octet-stream");
                    payload.put("data", batch.getFileData());
                    return payload;
                });
    }

    private void persistRows(CentreMonetiqueBatch batch,
                             List<CentreMonetiqueExtractionRow> rows,
                             CentreMonetiqueExtractionService.SummaryTotals summaryTotals) {
        batch.getTransactions().clear();

        BigDecimal totalMontant = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int txCount = 0;

        int i = 1;
        for (CentreMonetiqueExtractionRow row : rows) {
            CentreMonetiqueTransaction tx = new CentreMonetiqueTransaction();
            tx.setRowIndex(i++);
            tx.setSection(trimTo(row.getSection(), 64));
            tx.setDate(trimTo(row.getDate(), 16));
            tx.setReference(trimTo(row.getReference(), 32));
            tx.setDcFlag(trimTo(row.getDc(), 16));
            BigDecimal parsedMontant = safeForDb(scale4(parseDecimal(row.getMontant())));
            BigDecimal parsedDebit   = safeForDb(scale4(parseDecimal(row.getDebit())));
            BigDecimal parsedCredit  = safeForDb(scale4(parseDecimal(row.getCredit())));
            log.info("[persistRows] row={} section={} montantRaw='{}' montant={} debit={} credit={}",
                    i - 1, row.getSection(), row.getMontant(), parsedMontant, parsedDebit, parsedCredit);
            tx.setMontant(parsedMontant);
            tx.setDebit(parsedDebit);
            tx.setCredit(parsedCredit);
            batch.addTransaction(tx);

            String section = row.getSection() == null ? "" : row.getSection().trim().toUpperCase(Locale.ROOT);
            boolean summaryRow = section.startsWith("TOTAL")
                    || section.startsWith("SOLDE NET REMISE")
                    || section.startsWith("REGLEMENT META")
                    || section.equals("REMISE ACHAT")
                    || section.equals("AMEX SETTLEMENT")
                    || section.equals("AMEX TERMINAL")
                    || section.equals("AMEX TOTAL TERMINAL")
                    || section.equals("AMEX SUB TOTAL");
            if (summaryRow) {
                if (tx.getMontant() != null) {
                    totalMontant = totalMontant.add(tx.getMontant());
                }
                if (tx.getDebit() != null) {
                    totalDebit = totalDebit.add(tx.getDebit());
                }
                if (tx.getCredit() != null) {
                    totalCredit = totalCredit.add(tx.getCredit());
                }
            } else {
                txCount++;
            }
        }

        if (summaryTotals == null || summaryTotals.totalRemises() == null) {
            for (CentreMonetiqueExtractionRow row : rows) {
                if ("REMISE".equalsIgnoreCase(row.getSection())) {
                    BigDecimal amount = parseDecimal(row.getMontant());
                    if (amount != null) {
                        totalMontant = totalMontant.add(amount);
                    }
                }
            }
        }

        BigDecimal summaryRemises = summaryTotals != null ? summaryTotals.totalRemises() : null;
        BigDecimal summaryCommissionHt = summaryTotals != null ? summaryTotals.totalCommissionsHt() : null;
        BigDecimal summaryTva = summaryTotals != null ? summaryTotals.totalTvaSurCommissions() : null;
        BigDecimal summarySoldeNet = summaryTotals != null ? summaryTotals.soldeNetRemise() : null;
        BigDecimal summaryDebitNet = sumNullable(summaryCommissionHt, summaryTva);

        if (summaryRemises != null) {
            totalMontant = summaryRemises;
        }
        if (summaryDebitNet != null) {
            totalDebit = summaryDebitNet;
        }
        if (summarySoldeNet != null) {
            totalCredit = summarySoldeNet;
        }

        batch.setTransactionCount(txCount);
        batch.setStatementPeriod(deriveStatementPeriod(rows));
        batch.setTotalMontant(scale2(totalMontant));
        batch.setTotalDebit(scale2(totalDebit));
        batch.setTotalCredit(scale2(totalCredit));
        batch.setTotalCommissionHt(scale2(summaryCommissionHt));
        batch.setTotalTvaSurCommissions(scale2(summaryTva));
        batch.setSoldeNetRemise(scale2(summarySoldeNet != null ? summarySoldeNet : totalCredit));
    }

    private CentreMonetiqueBatchSummaryDTO toSummaryDTO(CentreMonetiqueBatch batch) {
        boolean isLinked = hasLinkedBankStatement(batch.getRib(), batch.getDossierId());
        return new CentreMonetiqueBatchSummaryDTO(
                batch.getId(),
                batch.getFilename(),
                batch.getOriginalName(),
                nvl(batch.getRib()),
                batch.getStatus(),
                nvl(batch.getStructure()),
                nvl(batch.getStatementPeriod()),
                String.valueOf(batch.getTransactionCount()),
                toAmount(batch.getTotalMontant()),
                toAmount(batch.getTotalCommissionHt()),
                toAmount(batch.getTotalTvaSurCommissions()),
                toAmount(batch.getSoldeNetRemise()),
                toAmount(batch.getTotalDebit()),
                toAmount(batch.getTotalCredit()),
                batch.getTransactionCount(),
                toDateTime(batch.getCreatedAt()),
                toDateTime(batch.getUpdatedAt()),
                batch.getErrorMessage(),
                isLinked,
                batch.isClientValidated(),
                toDateTime(batch.getClientValidatedAt()),
                batch.getClientValidatedBy());
    }

    private CentreMonetiqueBatchSummaryDTO toSummaryDTO(CentreMonetiqueBatchSummaryProjection batch) {
        String rib = nvl(batch.getRib());
        boolean isLinked = hasLinkedBankStatement(rib, null);
        return new CentreMonetiqueBatchSummaryDTO(
                batch.getId(),
                batch.getFilename(),
                batch.getOriginalName(),
                rib,
                batch.getStatus(),
                nvl(batch.getStructure()),
                nvl(batch.getStatementPeriod()),
                String.valueOf(batch.getTransactionCount() != null ? batch.getTransactionCount() : 0),
                toAmount(batch.getTotalMontant()),
                toAmount(batch.getTotalCommissionHt()),
                toAmount(batch.getTotalTvaSurCommissions()),
                toAmount(batch.getSoldeNetRemise()),
                toAmount(batch.getTotalDebit()),
                toAmount(batch.getTotalCredit()),
                batch.getTransactionCount() != null ? batch.getTransactionCount() : 0,
                toDateTime(batch.getCreatedAt()),
                toDateTime(batch.getUpdatedAt()),
                batch.getErrorMessage(),
                isLinked,
                Boolean.TRUE.equals(batch.getClientValidated()),
                toDateTime(batch.getClientValidatedAt()),
                batch.getClientValidatedBy());
    }

    private CentreMonetiqueBatchDetailDTO toDetailDTO(CentreMonetiqueBatch batch,
                                                      List<CentreMonetiqueExtractionRow> rows,
                                                      boolean includeRawOcr) {
        return new CentreMonetiqueBatchDetailDTO(
                batch.getId(),
                batch.getFilename(),
                batch.getOriginalName(),
                nvl(batch.getRib()),
                batch.getStatus(),
                nvl(batch.getStructure()),
                nvl(batch.getStatementPeriod()),
                batch.getFileContentType(),
                batch.getFileSize(),
                String.valueOf(batch.getTransactionCount()),
                toAmount(batch.getTotalMontant()),
                toAmount(batch.getTotalCommissionHt()),
                toAmount(batch.getTotalTvaSurCommissions()),
                toAmount(batch.getSoldeNetRemise()),
                toAmount(batch.getTotalDebit()),
                toAmount(batch.getTotalCredit()),
                batch.getTransactionCount(),
                toDateTime(batch.getCreatedAt()),
                toDateTime(batch.getUpdatedAt()),
                batch.getErrorMessage(),
                includeRawOcr ? batch.getRawOcrText() : null,
                batch.isClientValidated(),
                toDateTime(batch.getClientValidatedAt()),
                batch.getClientValidatedBy(),
                rows);
    }

    private List<CentreMonetiqueExtractionRow> toRows(List<CentreMonetiqueTransaction> transactions) {
        List<CentreMonetiqueExtractionRow> rows = new ArrayList<>();
        for (CentreMonetiqueTransaction tx : transactions) {
            rows.add(new CentreMonetiqueExtractionRow(
                    nvl(tx.getSection()),
                    nvl(tx.getDate()),
                    nvl(tx.getReference()),
                    toAmount(tx.getMontant()),
                    toAmount(tx.getDebit()),
                    toAmount(tx.getCredit()),
                    nvl(tx.getDcFlag())));
        }
        return rows;
    }

    private String toDateTime(LocalDateTime value) {
        return value == null ? "" : DATETIME_FMT.format(value);
    }

    private String toAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.HALF_UP);
        }
        return normalized.toPlainString();
    }

    private BigDecimal parseDecimal(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().replaceAll("\\s+", "");
        normalized = normalized.replace(',', '.');
        try {
            return new BigDecimal(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /** Retourne null si la valeur dépasse la capacité DECIMAL(18,4) → évite toute erreur DB. */
    private static final BigDecimal DB_MAX = new BigDecimal("99999999999999.9999"); // 14 chiffres entiers
    private BigDecimal safeForDb(BigDecimal value) {
        if (value == null) return null;
        if (value.abs().compareTo(DB_MAX) > 0) {
            log.warn("[persistRows] valeur hors-range DECIMAL(18,4) ignorée : {}", value);
            return null;
        }
        return value;
    }

    private BigDecimal scale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scale4(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal sumNullable(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return null;
        }
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    private String deriveStatementPeriod(List<CentreMonetiqueExtractionRow> rows) {
        LocalDate min = null;
        LocalDate max = null;
        for (CentreMonetiqueExtractionRow row : rows) {
            String section = row.getSection() == null ? "" : row.getSection().trim().toUpperCase(Locale.ROOT);
            boolean isDetailRow = "REMISE".equals(section)
                    || (section.startsWith("REMISE ") && !section.equals("REMISE ACHAT"))
                    || section.startsWith("REGLEMENT ")
                    || (section.startsWith("AMEX ") && !section.equals("AMEX SETTLEMENT")
                    && !section.equals("AMEX TERMINAL") && !section.equals("AMEX TOTAL TERMINAL")
                    && !section.equals("AMEX SUB TOTAL"))
                    || "VPS".equals(section);
            if (!isDetailRow || section.startsWith("REGLEMENT META")) {
                continue;
            }
            LocalDate parsed = parseRowDate(row.getDate());
            if (parsed == null) {
                continue;
            }
            if (min == null || parsed.isBefore(min)) {
                min = parsed;
            }
            if (max == null || parsed.isAfter(max)) {
                max = parsed;
            }
        }
        if (min == null || max == null) {
            return "";
        }
        String minPeriod = String.format("%02d/%04d", min.getMonthValue(), min.getYear());
        String maxPeriod = String.format("%02d/%04d", max.getMonthValue(), max.getYear());
        if (minPeriod.equals(maxPeriod)) {
            return minPeriod;
        }
        return minPeriod + " - " + maxPeriod;
    }

    private LocalDate parseRowDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        int firstSpace = normalized.indexOf(' ');
        if (firstSpace > 0) {
            normalized = normalized.substring(0, firstSpace);
        }
        String[] parts = normalized.split("/");
        if (parts.length != 3) {
            return null;
        }
        try {
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Integer.parseInt(parts[2]);
            year = year < 100 ? 2000 + year : year;
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String trimTo(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() <= maxLen) {
            return v;
        }
        return v.substring(0, maxLen);
    }

    private String limitError(String message) {
        if (message == null || message.isBlank()) {
            return "Erreur inconnue";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private Optional<CentreMonetiqueBatch> findBatchForDossier(Long id, Long resolvedDossierId) {
        if (id == null || resolvedDossierId == null) {
            return Optional.empty();
        }
        return batchRepository.findById(id)
                .filter(batch -> belongsToDossierOrLegacyNull(batch.getDossierId(), resolvedDossierId));
    }

    private boolean belongsToDossierOrLegacyNull(Long recordDossierId, Long activeDossierId) {
        return recordDossierId == null || activeDossierId == null || recordDossierId.equals(activeDossierId);
    }

    private Optional<String> resolveAmexRibFromLast5(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = AMEX_IBAN_LAST5_PATTERN.matcher(rawOcrText);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String suffix = matcher.group(1);
        if (suffix == null || suffix.length() != 5) {
            return Optional.empty();
        }
        List<String> candidates = bankStatementRepository.findDistinctRibsEndingWith(suffix).stream()
                .filter(r -> r != null && !r.isBlank() && r.endsWith(suffix))
                .distinct()
                .toList();
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        // Fallback: expose at least the IBAN last 5 digits in the RIB column.
        return Optional.of(suffix);
    }

    private String normalizeRibDigits(String rib) {
        if (rib == null) {
            return null;
        }
        String digits = rib.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private boolean hasLinkedBankStatement(String rib, Long dossierId) {
        String normalizedRib = normalizeRibDigits(rib);
        if (normalizedRib == null || normalizedRib.isBlank()) {
            return false;
        }
        if (hasBankStatementForRib(normalizedRib, dossierId)) {
            return true;
        }

        // AMEX et certains batchs historiques peuvent n'avoir que les 5 derniers chiffres.
        // Si on a un suffixe court, on cherche les relevés bancaires qui se terminent pareil.
        if (normalizedRib.length() <= 5) {
            return hasBankStatementForRibSuffix(normalizedRib, dossierId);
        }
        return false;
    }

    private boolean hasBankStatementForRib(String normalizedRib, Long dossierId) {
        if (dossierId == null) {
            return bankStatementRepository.countByRib(normalizedRib) > 0;
        }
        // Include legacy statements with null dossier_id (uploaded before multi-dossier support)
        return bankStatementRepository.countByRibInDossierOrLegacy(normalizedRib, dossierId) > 0;
    }

    private boolean hasBankStatementForRibSuffix(String suffix, Long dossierId) {
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        for (String candidateRib : bankStatementRepository.findDistinctRibsEndingWith(suffix)) {
            String normalizedCandidate = normalizeRibDigits(candidateRib);
            if (normalizedCandidate == null || normalizedCandidate.isBlank()) {
                continue;
            }
            if (hasBankStatementForRib(normalizedCandidate, dossierId)) {
                return true;
            }
        }
        return false;
    }
}
