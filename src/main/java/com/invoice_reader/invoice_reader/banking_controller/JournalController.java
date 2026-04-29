package com.invoice_reader.invoice_reader.banking_controller;

import com.invoice_reader.invoice_reader.entity.accounting.AccountingEntry;
import com.invoice_reader.invoice_reader.banking_entity.JournalBatch;
import com.invoice_reader.invoice_reader.banking_entity.JournalEntry;
import com.invoice_reader.invoice_reader.banking_repository.AccountingEntryRepository;
import com.invoice_reader.invoice_reader.banking_repository.JournalBatchRepository;
import com.invoice_reader.invoice_reader.banking_repository.JournalEntryRepository;
import com.invoice_reader.invoice_reader.banking_services.ComptabilisationWorkflowService;
import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_repository.BankStatementRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/journals", "/api/v2/journals"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class JournalController {

    private static final String JOURNAL_LABEL = "Simulation de Comptabilisation";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BankStatementRepository bankStatementRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final ComptabilisationWorkflowService workflowService;
    private final JournalBatchRepository journalBatchRepository;
    private final JournalEntryRepository journalEntryRepository;

    @GetMapping("/periods")
    public ResponseEntity<?> listPeriods() {
        seedJournalBatches(bankStatementRepository.findAllWithPeriodOrderByYearMonthDesc());
        List<Object[]> raw = journalBatchRepository.findDistinctPeriods();
        List<JournalPeriod> periods = new ArrayList<>(raw.size());
        for (Object[] row : raw) {
            Integer year = row[0] != null ? ((Number) row[0]).intValue() : null;
            Integer month = row[1] != null ? ((Number) row[1]).intValue() : null;
            if (year == null || month == null) {
                continue;
            }
            periods.add(new JournalPeriod(year, month, formatPeriodKey(year, month)));
        }
        return ResponseEntity.ok(Map.of("periods", periods));
    }

    @GetMapping
    public ResponseEntity<?> listJournals(@RequestParam(name = "period", required = false) String period) {
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

        List<BankStatement> statements = (year != null && month != null)
                ? bankStatementRepository.findByYearAndMonthOrderByCreatedAtDesc(year, month)
                : bankStatementRepository.findAllWithPeriodOrderByYearMonthDesc();

        seedJournalBatches(statements);
        List<JournalItem> items = new ArrayList<>(statements.size());
        for (BankStatement statement : statements) {
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

    @PostMapping("/{statementId}/export")
    public ResponseEntity<?> exportJournal(
            @PathVariable("statementId") Long statementId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        JournalExportPayload payload = preparePayload(statementId, userId);
        String csv = buildCsv(payload.rows());
        String filename = payload.exportFilename() + ".csv";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{statementId}/print")
    public ResponseEntity<?> printJournal(
            @PathVariable("statementId") Long statementId,
            @RequestHeader(name = "X-User-Id", required = false) String userId) {
        JournalExportPayload payload = preparePayload(statementId, userId);
        String html = buildPrintHtml(payload);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                .body(html);
    }

    @GetMapping("/{statementId}/entries")
    public ResponseEntity<?> getJournalEntries(@PathVariable("statementId") Long statementId) {
        if (statementId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "statementId est obligatoire."));
        }
        JournalBatch batch = journalBatchRepository.findByStatementId(statementId).orElse(null);
        if (batch == null) {
            return ResponseEntity.ok(Map.of("entries", List.of()));
        }
        List<JournalEntry> entries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(batch.getId());
        List<JournalEntryRow> rows = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            rows.add(JournalEntryRow.fromJournalEntry(entry));
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
            if (entries.isEmpty()) {
                BankStatement statement = bankStatementRepository.findById(statementId)
                        .orElseThrow(() -> new IllegalArgumentException("Relevé introuvable."));
                ensureJournalEntries(existing, statement, userId);
                entries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(existing.getId());
            }
            List<JournalRow> rows = new ArrayList<>(entries.size());
            for (JournalEntry entry : entries) {
                rows.add(JournalRow.fromJournalEntry(entry));
            }
            String exportFilename = buildExportFilename(existing);
            return new JournalExportPayload(existing, rows, exportFilename);
        }

        BankStatement statement = bankStatementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Relevé introuvable."));
        if (statement.getYear() == null || statement.getMonth() == null) {
            throw new IllegalArgumentException("Période du relevé indisponible.");
        }

        JournalBatch batch = buildBatchFromStatement(statement, userId);
        JournalBatch savedBatch = journalBatchRepository.save(batch);
        List<JournalRow> rows = ensureJournalEntries(savedBatch, statement, userId);
        String exportFilename = buildExportFilename(savedBatch);
        return new JournalExportPayload(savedBatch, rows, exportFilename);
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

    private void seedJournalBatches(List<BankStatement> statements) {
        for (BankStatement statement : statements) {
            if (statement.getYear() == null || statement.getMonth() == null) {
                continue;
            }
            if (journalBatchRepository.findByStatementId(statement.getId()).isPresent()) {
                continue;
            }
            JournalBatch batch = buildBatchFromStatement(statement, "system");
            journalBatchRepository.save(batch);
        }
    }

    private JournalBatch buildBatchFromStatement(BankStatement statement, String userId) {
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

    private List<JournalRow> ensureJournalEntries(JournalBatch batch, BankStatement statement, String userId) {
        if (journalEntryRepository.countByBatchId(batch.getId()) > 0) {
            List<JournalEntry> existingEntries = journalEntryRepository.findByBatchIdOrderByNumeroAscIdAsc(batch.getId());
            List<JournalRow> rows = new ArrayList<>(existingEntries.size());
            for (JournalEntry entry : existingEntries) {
                rows.add(JournalRow.fromJournalEntry(entry));
            }
            return rows;
        }
        if (accountingEntryRepository.countBySourceStatementId(statement.getId()) == 0) {
            ComptabilisationWorkflowService.SimulationResult simulation = workflowService.simulate(statement.getId());
            workflowService.confirm(simulation.simulationId(), userId);
        }

        workflowService.syncCptjournalFromExistingAccountingEntries(statement.getId());
        List<AccountingEntry> entries = accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statement.getId());

        List<JournalRow> rows = new ArrayList<>(entries.size());
        for (AccountingEntry entry : entries) {
            JournalEntry journalEntry = new JournalEntry();
            journalEntry.setBatch(batch);
            journalEntry.setNumero(entry.getNumero());
            journalEntry.setMois(entry.getMois());
            journalEntry.setNmois(entry.getNmois());
            journalEntry.setDateComplete(entry.getDateComplete());
            journalEntry.setJournal(entry.getNdosjrn());
            journalEntry.setNcompte(entry.getNcompte());
            journalEntry.setLibelle(entry.getEcriture());
            journalEntry.setDebit(entry.getDebit());
            journalEntry.setCredit(entry.getCredit());
            journalEntry.setSourceTransactionId(entry.getSourceTransactionId());
            journalEntry.setIsCounterpart(entry.getIsCounterpart());
            batch.getEntries().add(journalEntry);
            rows.add(JournalRow.fromAccountingEntry(entry));
        }
        journalBatchRepository.save(batch);
        return rows;
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
}
