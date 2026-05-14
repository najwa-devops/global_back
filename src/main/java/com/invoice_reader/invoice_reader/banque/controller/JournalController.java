package com.invoice_reader.invoice_reader.banque.controller;

import com.invoice_reader.invoice_reader.database.entity.accounting.AccountingEntry;
import com.invoice_reader.invoice_reader.banque.entity.JournalBatch;
import com.invoice_reader.invoice_reader.banque.entity.JournalEntry;
import com.invoice_reader.invoice_reader.banque.repository.AccountingEntryRepository;
import com.invoice_reader.invoice_reader.banque.repository.JournalBatchRepository;
import com.invoice_reader.invoice_reader.banque.repository.JournalEntryRepository;
import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.auth.service.SessionKeys;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

@RestController
@RequestMapping({"/api/journals", "/api/v2/journals"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class JournalController {

    private static final String JOURNAL_LABEL = "Simulation de Comptabilisation";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BanqueReleveRepository bankStatementRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final JournalBatchRepository journalBatchRepository;
    private final JournalEntryRepository journalEntryRepository;

    @GetMapping("/periods")
    public ResponseEntity<?> listPeriods(
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        Long effectiveDossierId = resolveEffectiveDossierId(dossierId, session);
        List<BanqueReleve> statements = effectiveDossierId != null
                ? bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(effectiveDossierId)
                        .stream().filter(s -> s.getYear() != null && s.getMonth() != null).toList()
                : bankStatementRepository.findAllWithPeriodOrderByYearMonthDesc();
        // Compute distinct periods from filtered statements (preserves dossier scope)
        Map<String, JournalPeriod> seen = new LinkedHashMap<>();
        for (BanqueReleve s : statements) {
            if (s.getYear() == null || s.getMonth() == null) continue;
            String key = formatPeriodKey(s.getYear(), s.getMonth());
            seen.putIfAbsent(key, new JournalPeriod(s.getYear(), s.getMonth(), key));
        }
        return ResponseEntity.ok(Map.of("periods", new ArrayList<>(seen.values())));
    }

    @GetMapping
    public ResponseEntity<?> listJournals(
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            HttpSession session) {
        Long effectiveDossierId = resolveEffectiveDossierId(dossierId, session);
        Integer year = null;
        Integer month = null;
        if (period != null && !period.isBlank()) {
            String[] parts = period.trim().split("-");
            if (parts.length == 2) {
                try {
                    year = Integer.parseInt(parts[0]);
                    month = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Période invalide. Format attendu: YYYY-MM"));
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Période invalide. Format attendu: YYYY-MM"));
            }
        }

        List<BanqueReleve> statements;
        if (year != null && month != null) {
            statements = effectiveDossierId != null
                    ? bankStatementRepository.findByDossierIdAndYearAndMonthOrderByCreatedAtDesc(effectiveDossierId, year, month)
                    : bankStatementRepository.findByYearAndMonthOrderByCreatedAtDesc(year, month);
        } else {
            statements = effectiveDossierId != null
                    ? bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(effectiveDossierId)
                            .stream().filter(s -> s.getYear() != null && s.getMonth() != null).toList()
                    : bankStatementRepository.findAllWithPeriodOrderByYearMonthDesc();
        }

        List<JournalItem> items = new ArrayList<>(statements.size());
        for (BanqueReleve statement : statements) {
            if (statement.getYear() == null || statement.getMonth() == null) {
                continue;
            }
            items.add(new JournalItem(
                    statement.getId(),
                    statement.getOriginalName(),
                    statement.getFilename(),
                    statement.getYear(),
                    statement.getMonth(),
                    statement.getStatus() != null ? statement.getStatus().name() : null,
                    JOURNAL_LABEL
            ));
        }
        return ResponseEntity.ok(Map.of("journals", items));
    }

    @GetMapping("/all-entries")
    public ResponseEntity<?> getAllJournalEntries(
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            @RequestParam(name = "year", required = false) Integer year,
            HttpSession session) {
        Long effectiveDossierId = resolveEffectiveDossierId(dossierId, session);
        AllEntriesPayload payload = collectAllEntries(effectiveDossierId, year);
        return ResponseEntity.ok(Map.of(
                "entries", payload.entries(),
                "totalDebit", payload.totalDebit(),
                "totalCredit", payload.totalCredit(),
                "solde", payload.solde(),
                "balanced", payload.balanced(),
                "count", payload.entries().size(),
                "availableYears", payload.availableYears(),
                "availableJournals", payload.availableJournals()
        ));
    }

    @PostMapping("/export-all")
    public ResponseEntity<?> exportAllJournal(
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            HttpSession session) {
        Long effectiveDossierId = resolveEffectiveDossierId(dossierId, session);
        AllEntriesPayload payload = collectAllEntries(effectiveDossierId, year);
        List<JournalRow> rows = new ArrayList<>(payload.entries().size());
        for (JournalEntryRow r : payload.entries()) {
            rows.add(new JournalRow(r.numero(), r.mois(), r.nmois(), r.date(), r.journal(), r.compte(), r.libelle(), r.debit(), r.credit(), r.sourceTransactionId(), r.counterpart()));
        }
        String csv = buildCsv(rows);
        String period = year != null ? String.valueOf(year) : "all";
        String filename = "journal_comptable_banque_" + period + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/print-all")
    public ResponseEntity<?> printAllJournal(
            @RequestParam(name = "dossierId", required = false) Long dossierId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestHeader(name = "X-User-Id", required = false) String userId,
            HttpSession session) {
        Long effectiveDossierId = resolveEffectiveDossierId(dossierId, session);
        AllEntriesPayload payload = collectAllEntries(effectiveDossierId, year);
        String html = buildPrintHtmlAll(payload, year);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                .body(html);
    }

    private AllEntriesPayload collectAllEntries(Long effectiveDossierId, Integer year) {
        List<BanqueReleve> statements = effectiveDossierId != null
                ? bankStatementRepository.findByDossierIdOrderByCreatedAtDesc(effectiveDossierId)
                        .stream().filter(s -> s.getYear() != null && s.getMonth() != null
                                && (year == null || year.equals(s.getYear()))).toList()
                : bankStatementRepository.findAllWithPeriodOrderByYearMonthDesc()
                        .stream().filter(s -> year == null || year.equals(s.getYear())).toList();

        List<JournalEntryRow> allRows = new ArrayList<>();
        for (BanqueReleve statement : statements) {
            // First: try journal_entry table (already-built cache)
            JournalBatch batch = journalBatchRepository.findByStatementId(statement.getId()).orElse(null);
            if (batch != null) {
                List<JournalEntry> entries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(batch.getId());
                if (!entries.isEmpty()) {
                    for (JournalEntry e : entries) {
                        allRows.add(JournalEntryRow.fromJournalEntry(e));
                    }
                    continue;
                }
            }
            // Fallback: read directly from accounting_entries (no side effects, no lazy-load risk)
            List<AccountingEntry> accountingEntries =
                    accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statement.getId());
            for (AccountingEntry e : accountingEntries) {
                String date = e.getDateComplete() != null ? e.getDateComplete().format(DATE_FORMAT) : "";
                allRows.add(new JournalEntryRow(
                        e.getNumero() != null ? e.getNumero() : 0L,
                        e.getMois(),
                        e.getNmois() != null ? e.getNmois() : 0,
                        date,
                        e.getNdosjrn(),
                        e.getNcompte(),
                        e.getEcriture(),
                        e.getDebit(),
                        e.getCredit(),
                        e.getSourceTransactionId(),
                        Boolean.TRUE.equals(e.getIsCounterpart())
                ));
            }
        }

        BigDecimal totalDebit = allRows.stream()
                .map(r -> r.debit() != null ? r.debit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = allRows.stream()
                .map(r -> r.credit() != null ? r.credit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal solde = totalDebit.subtract(totalCredit);
        List<Integer> availableYears = statements.stream()
                .map(BanqueReleve::getYear).filter(y -> y != null)
                .distinct().sorted(Comparator.reverseOrder()).toList();
        List<String> availableJournals = allRows.stream()
                .map(JournalEntryRow::journal).filter(j -> j != null && !j.isBlank())
                .distinct().sorted().toList();
        return new AllEntriesPayload(allRows, formatAmount(totalDebit), formatAmount(totalCredit),
                formatAmount(solde), solde.compareTo(BigDecimal.ZERO) == 0, availableYears, availableJournals);
    }

    private String buildPrintHtmlAll(AllEntriesPayload payload, Integer year) {
        String periodLabel = year != null ? "Exercice " + year : "Toutes les périodes";
        String printDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\"/>")
                .append("<title>Journal Comptable Banque</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:24px;color:#111}")
                .append("h1{font-size:20px;margin:0 0 4px}")
                .append(".meta{margin-bottom:8px;font-size:12px;color:#444}")
                .append(".stats{margin-bottom:16px;font-size:13px;font-weight:bold}")
                .append(".debit-val{color:#dc2626}.credit-val{color:#166534}")
                .append("table{width:100%;border-collapse:collapse;font-size:11px}")
                .append("th{background:#f2f2f2;border:1px solid #ddd;padding:5px 6px;text-align:left;font-weight:600;text-transform:uppercase}")
                .append("td{border:1px solid #ddd;padding:4px 6px}")
                .append(".num{text-align:right}.debit{text-align:right;color:#dc2626;font-weight:600}")
                .append(".credit{text-align:right;font-weight:600}.dash{text-align:right;color:#aaa}")
                .append("</style></head><body>")
                .append("<h1>Journal Comptable Banque</h1>")
                .append("<div class=\"meta\">").append(escapeHtml(periodLabel))
                .append(" | Imprimé le ").append(escapeHtml(printDate))
                .append(" | ").append(payload.entries().size()).append(" écritures</div>")
                .append("<div class=\"stats\">")
                .append("Débit: <span class=\"debit-val\">").append(escapeHtml(payload.totalDebit())).append("</span>")
                .append(" &nbsp; Crédit: <span class=\"credit-val\">").append(escapeHtml(payload.totalCredit())).append("</span>")
                .append(" &nbsp; Solde: ").append(escapeHtml(payload.solde()))
                .append(payload.balanced() ? " (Equilibre)" : "")
                .append("</div>")
                .append("<table><thead><tr>")
                .append("<th>Numéro</th><th>Date</th><th>Journal</th><th>N° Compte</th><th>Libellé</th><th>Débit</th><th>Crédit</th>")
                .append("</tr></thead><tbody>");
        for (JournalEntryRow row : payload.entries()) {
            boolean hasDebit = row.debit() != null && row.debit().compareTo(BigDecimal.ZERO) > 0;
            boolean hasCredit = row.credit() != null && row.credit().compareTo(BigDecimal.ZERO) > 0;
            html.append("<tr>")
                    .append("<td class=\"num\">").append(row.numero()).append("</td>")
                    .append("<td>").append(escapeHtml(row.date())).append("</td>")
                    .append("<td>").append(escapeHtml(row.journal())).append("</td>")
                    .append("<td>").append(escapeHtml(row.compte())).append("</td>")
                    .append("<td>").append(escapeHtml(row.libelle())).append("</td>")
                    .append("<td class=\"").append(hasDebit ? "debit" : "dash").append("\">")
                    .append(hasDebit ? escapeHtml(formatAmount(row.debit())) : "-").append("</td>")
                    .append("<td class=\"").append(hasCredit ? "credit" : "dash").append("\">")
                    .append(hasCredit ? escapeHtml(formatAmount(row.credit())) : "-").append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>")
                .append("<script>window.onload=function(){window.print();}</script>")
                .append("</body></html>");
        return html.toString();
    }

    private Long resolveEffectiveDossierId(Long requestedDossierId, HttpSession session) {
        if (requestedDossierId != null) {
            session.setAttribute(SessionKeys.ACTIVE_DOSSIER_ID, requestedDossierId);
            return requestedDossierId;
        }
        if (session == null) return null;
        Object rawId = session.getAttribute(SessionKeys.ACTIVE_DOSSIER_ID);
        if (rawId == null) return null;
        try {
            return Long.valueOf(rawId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PostMapping("/{statementId}/export")
    public ResponseEntity<?> exportJournal(
            @PathVariable("statementId") Long statementId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        try {
            JournalExportPayload payload = preparePayload(statementId, userId);
            String csv = buildCsv(payload.rows());
            String filename = payload.exportFilename() + ".csv";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(csv.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{statementId}/print")
    public ResponseEntity<?> printJournal(
            @PathVariable("statementId") Long statementId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        try {
            JournalExportPayload payload = preparePayload(statementId, userId);
            String html = buildPrintHtml(payload);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                    .body(html);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{statementId}/entries")
    public ResponseEntity<?> getJournalEntries(@PathVariable("statementId") Long statementId) {
        if (statementId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "statementId est obligatoire."));
        }
        // Try journal_entry cache first
        JournalBatch batch = journalBatchRepository.findByStatementId(statementId).orElse(null);
        if (batch != null) {
            List<JournalEntry> entries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(batch.getId());
            if (!entries.isEmpty()) {
                List<JournalEntryRow> rows = new ArrayList<>(entries.size());
                for (JournalEntry entry : entries) {
                    rows.add(JournalEntryRow.fromJournalEntry(entry));
                }
                return ResponseEntity.ok(Map.of("entries", rows));
            }
        }
        // Fallback: read directly from accounting_entries (no lazy-load risk, no side effects)
        List<AccountingEntry> accountingEntries =
                accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statementId);
        List<JournalEntryRow> rows = new ArrayList<>(accountingEntries.size());
        for (AccountingEntry e : accountingEntries) {
            String date = e.getDateComplete() != null ? e.getDateComplete().format(DATE_FORMAT) : "";
            rows.add(new JournalEntryRow(
                    e.getNumero() != null ? e.getNumero() : 0L,
                    e.getMois(),
                    e.getNmois() != null ? e.getNmois() : 0,
                    date,
                    e.getNdosjrn(),
                    e.getNcompte(),
                    e.getEcriture(),
                    e.getDebit(),
                    e.getCredit(),
                    e.getSourceTransactionId(),
                    Boolean.TRUE.equals(e.getIsCounterpart())
            ));
        }
        return ResponseEntity.ok(Map.of("entries", rows));
    }

    private JournalExportPayload preparePayload(Long statementId, String userId) {
        if (statementId == null) {
            throw new IllegalArgumentException("statementId est obligatoire.");
        }
        JournalBatch existing = journalBatchRepository.findByStatementId(statementId).orElse(null);
        if (existing != null) {
            List<JournalEntry> entries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(existing.getId());
            List<JournalRow> rows;
            if (!entries.isEmpty()) {
                rows = new ArrayList<>(entries.size());
                for (JournalEntry entry : entries) {
                    rows.add(JournalRow.fromJournalEntry(entry));
                }
            } else {
                List<AccountingEntry> accountingEntries =
                        accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statementId);
                if (accountingEntries.isEmpty()) {
                    throw new IllegalStateException("Aucune ecriture comptable disponible pour ce releve. Lancez d'abord la simulation puis la confirmation.");
                }
                rows = new ArrayList<>(accountingEntries.size());
                for (AccountingEntry e : accountingEntries) {
                    rows.add(JournalRow.fromAccountingEntry(e));
                }
            }
            String exportFilename = buildExportFilename(existing);
            return new JournalExportPayload(existing, rows, exportFilename);
        }

        BanqueReleve statement = bankStatementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Relevé introuvable."));
        if (statement.getYear() == null || statement.getMonth() == null) {
            throw new IllegalArgumentException("Période du relevé indisponible.");
        }

        List<AccountingEntry> accountingEntries = accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statementId);
        if (accountingEntries.isEmpty()) {
            throw new IllegalStateException("Aucune ecriture comptable disponible pour ce releve. Lancez d'abord la simulation puis la confirmation.");
        }

        JournalBatch batch = buildBatchFromStatement(statement, userId);
        List<JournalRow> rows = new ArrayList<>(accountingEntries.size());
        for (AccountingEntry entry : accountingEntries) {
            rows.add(JournalRow.fromAccountingEntry(entry));
        }
        String exportFilename = buildExportFilename(batch);
        return new JournalExportPayload(batch, rows, exportFilename);
    }

    private String buildCsv(List<JournalRow> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("Numero,Mois,Nmois,Date,Journal,Compte,Libelle,Debit,Credit,SourceTransactionId,Counterpart\n");
        for (JournalRow row : rows) {
            builder.append(row.numero()).append(',')
                    .append(escapeCsv(row.mois())).append(',')
                    .append(row.nmois()).append(',')
                    .append(escapeCsv(row.date())).append(',')
                    .append(escapeCsv(row.journal())).append(',')
                    .append(escapeCsv(row.compte())).append(',')
                    .append(escapeCsv(row.libelle())).append(',')
                    .append(formatAmount(row.debit())).append(',')
                    .append(formatAmount(row.credit())).append(',')
                    .append(row.sourceTransactionId() != null ? row.sourceTransactionId() : "")
                    .append(',')
                    .append(row.counterpart())
                    .append('\n');
        }
        return builder.toString();
    }

    private String buildPrintHtml(JournalExportPayload payload) {
        JournalBatch batch = payload.batch();
        String period = formatPeriodKey(batch.getYear(), batch.getMonth());
        String title = "Journal - " + JOURNAL_LABEL;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\"/>")
                .append("<title>").append(escapeHtml(title)).append("</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:24px;color:#111}")
                .append("h1{font-size:20px;margin:0 0 8px}")
                .append(".meta{margin-bottom:16px;font-size:12px;color:#444}")
                .append("table{width:100%;border-collapse:collapse;font-size:12px}")
                .append("th,td{border:1px solid #ddd;padding:6px;text-align:left}")
                .append("th{background:#f2f2f2}")
                .append("</style></head><body>")
                .append("<h1>").append(escapeHtml(title)).append("</h1>")
                .append("<div class=\"meta\">")
                .append("Relevé: ").append(escapeHtml(Optional.ofNullable(batch.getOriginalName()).orElse(batch.getFilename())))
                .append(" | Période: ").append(escapeHtml(period))
                .append("</div>")
                .append("<table><thead><tr>")
                .append("<th>Numero</th><th>Mois</th><th>Nmois</th><th>Date</th><th>Journal</th><th>Compte</th>")
                .append("<th>Libellé</th><th>Débit</th><th>Crédit</th><th>Source Tx</th><th>Contrepartie</th>")
                .append("</tr></thead><tbody>");
        for (JournalRow row : payload.rows()) {
            html.append("<tr>")
                    .append("<td>").append(row.numero()).append("</td>")
                    .append("<td>").append(escapeHtml(row.mois())).append("</td>")
                    .append("<td>").append(row.nmois()).append("</td>")
                    .append("<td>").append(escapeHtml(row.date())).append("</td>")
                    .append("<td>").append(escapeHtml(row.journal())).append("</td>")
                    .append("<td>").append(escapeHtml(row.compte())).append("</td>")
                    .append("<td>").append(escapeHtml(row.libelle())).append("</td>")
                    .append("<td>").append(escapeHtml(formatAmount(row.debit()))).append("</td>")
                    .append("<td>").append(escapeHtml(formatAmount(row.credit()))).append("</td>")
                    .append("<td>").append(row.sourceTransactionId() != null ? row.sourceTransactionId() : "").append("</td>")
                    .append("<td>").append(row.counterpart() ? "Oui" : "Non").append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>")
                .append("<script>window.onload=function(){window.print();}</script>")
                .append("</body></html>");
        return html.toString();
    }

    private static String formatAmount(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String formatPeriodKey(Integer year, Integer month) {
        if (year == null || month == null) return "";
        return String.format(Locale.ROOT, "%04d-%02d", year, month);
    }

    private static String buildExportFilename(JournalBatch batch) {
        String base = Optional.ofNullable(batch.getOriginalName()).orElse(batch.getFilename());
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        String period = formatPeriodKey(batch.getYear(), batch.getMonth());
        if (!period.isBlank()) {
            return "journal_" + period + "_" + sanitized;
        }
        return "journal_" + sanitized;
    }

    private JournalBatch buildBatchFromStatement(BanqueReleve statement, String userId) {
        JournalBatch batch = new JournalBatch();
        batch.setStatementId(statement.getId());
        batch.setFilename(statement.getFilename());
        batch.setOriginalName(statement.getOriginalName());
        batch.setYear(statement.getYear());
        batch.setMonth(statement.getMonth());
        batch.setLabel(JOURNAL_LABEL);
        batch.setCreatedBy((userId == null || userId.isBlank()) ? "system" : userId);
        return batch;
    }

    private record JournalPeriod(Integer year, Integer month, String key) {
    }

    private record JournalItem(
            Long statementId,
            String originalName,
            String filename,
            Integer year,
            Integer month,
            String status,
            String label
    ) {
    }

    private record JournalExportPayload(
            JournalBatch batch,
            List<JournalRow> rows,
            String exportFilename
    ) {
    }

    private record JournalRow(
            long numero,
            String mois,
            int nmois,
            String date,
            String journal,
            String compte,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            Long sourceTransactionId,
            boolean counterpart
    ) {
        static JournalRow fromAccountingEntry(AccountingEntry entry) {
            String date = entry.getDateComplete() != null ? entry.getDateComplete().format(DATE_FORMAT) : "";
            return new JournalRow(
                    entry.getNumero() != null ? entry.getNumero() : 0L,
                    entry.getMois(),
                    entry.getNmois() != null ? entry.getNmois() : 0,
                    date,
                    entry.getNdosjrn(),
                    entry.getNcompte(),
                    entry.getEcriture(),
                    entry.getDebit(),
                    entry.getCredit(),
                    entry.getSourceTransactionId(),
                    Boolean.TRUE.equals(entry.getIsCounterpart())
            );
        }

        static JournalRow fromJournalEntry(JournalEntry entry) {
            String date = entry.getDateComplete() != null ? entry.getDateComplete().format(DATE_FORMAT) : "";
            return new JournalRow(
                    entry.getNumero() != null ? entry.getNumero() : 0L,
                    entry.getMois(),
                    entry.getNmois() != null ? entry.getNmois() : 0,
                    date,
                    entry.getJournal(),
                    entry.getNcompte(),
                    entry.getLibelle(),
                    entry.getDebit(),
                    entry.getCredit(),
                    entry.getSourceTransactionId(),
                    Boolean.TRUE.equals(entry.getIsCounterpart())
            );
        }
    }

    private record JournalEntryRow(
            long numero,
            String mois,
            int nmois,
            String date,
            String journal,
            String compte,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            Long sourceTransactionId,
            boolean counterpart
    ) {
        static JournalEntryRow fromJournalEntry(JournalEntry entry) {
            String date = entry.getDateComplete() != null ? entry.getDateComplete().format(DATE_FORMAT) : "";
            return new JournalEntryRow(
                    entry.getNumero() != null ? entry.getNumero() : 0L,
                    entry.getMois(),
                    entry.getNmois() != null ? entry.getNmois() : 0,
                    date,
                    entry.getJournal(),
                    entry.getNcompte(),
                    entry.getLibelle(),
                    entry.getDebit(),
                    entry.getCredit(),
                    entry.getSourceTransactionId(),
                    Boolean.TRUE.equals(entry.getIsCounterpart())
            );
        }
    }

    private record AllEntriesPayload(
            List<JournalEntryRow> entries,
            String totalDebit,
            String totalCredit,
            String solde,
            boolean balanced,
            List<Integer> availableYears,
            List<String> availableJournals
    ) {
    }
}
