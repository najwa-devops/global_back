package com.invoice_reader.invoice_reader.banque.service;

import com.invoice_reader.invoice_reader.database.entity.accounting.AccountingEntry;
import com.invoice_reader.invoice_reader.banque.repository.AccountingEntryRepository;
import com.invoice_reader.invoice_reader.banque.repository.CptjournalJdbcRepository;
import com.invoice_reader.invoice_reader.banque.repository.CptjournalSyncTrackerRepository;
import com.invoice_reader.invoice_reader.banque.liaison.dto.CmExpansionDTO;
import com.invoice_reader.invoice_reader.banque.liaison.service.CentreMonetiqueLiaisonService;
import com.invoice_reader.invoice_reader.banque.entity.BanqueReleve;
import com.invoice_reader.invoice_reader.banque.entity.BanqueStatus;
import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import com.invoice_reader.invoice_reader.banque.repository.BanqueReleveRepository;
import com.invoice_reader.invoice_reader.banque.repository.BanqueTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ComptabilisationWorkflowService {

    private static final String DEFAULT_TX_COMPTE = "349700000";
    private static final String ACCOUNT_CODE_REGEX = "^\\d{9}$";
    private static final String VALIDER_TRUE = "1";
    private static final String TOTAL_REMISE_COMPTE = "349700000";
    private static final String COMMISSION_COMPTE = "614700000";
    private static final String TVA_COMPTE = "345520100";

    private final BanqueReleveRepository bankStatementRepository;
    private final BanqueTransactionRepository bankTransactionRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final CptjournalJdbcRepository cptjournalJdbcRepository;
    private final CptjournalSyncTrackerRepository cptjournalSyncTrackerRepository;
    private final CentreMonetiqueLiaisonService centreMonetiqueLiaisonService;

    @Value("${accounting.default-journal:BQ}")
    private String defaultJournal;

    @Value("${accounting.principal-account:514100000}")
    private String principalAccount;

    private final Map<String, SimulationContext> simulations = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public SimulationResult simulate(Long statementId) {
        if (statementId == null) {
            throw new IllegalArgumentException("statementId est obligatoire.");
        }

        BanqueReleve statement = bankStatementRepository.findById(statementId)
                .orElseThrow(() -> new IllegalArgumentException("Releve introuvable."));

        List<BanqueTransaction> transactions = bankTransactionRepository
                .findByStatementIdOrderByTransactionIndexAsc(statementId);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction a comptabiliser pour ce releve.");
        }

        String journal = sanitizeJournal(defaultJournal);
        String bankAccount = sanitizeAccount(principalAccount, "compte principal");

        int nmois = resolveMonth(statement, transactions);
        String moisTexte = monthLabel(nmois);
        String nmoisTexte = String.format("%02d", nmois);
        long fallbackNumero = accountingEntryRepository.findMaxNumeroByJournalAndMonth(journal, nmois) + 1;

        List<CmExpansionDTO> cmExpansions = centreMonetiqueLiaisonService.getCmExpansionsForStatement(statementId);
        Map<Long, CmExpansionDTO> cmByTxId = new ConcurrentHashMap<>();
        for (CmExpansionDTO exp : cmExpansions) {
            cmByTxId.put(exp.bankTransactionId(), exp);
        }

        List<SimulatedEntry> rows = new ArrayList<>();
        for (BanqueTransaction tx : transactions) {
            LocalDate dateOperation = tx.getDateOperation();
            if (dateOperation == null) {
                continue;
            }

            BigDecimal debit = tx.getDebit() == null ? BigDecimal.ZERO : tx.getDebit();
            BigDecimal credit = tx.getCredit() == null ? BigDecimal.ZERO : tx.getCredit();
            String libelle = tx.getLibelle() == null ? "" : tx.getLibelle();
            String contrepartie = sanitizeTransactionAccount(tx.getCompte());
            CmExpansionDTO cmExp = cmByTxId.get(tx.getId());
            long numeroMain = resolveTransactionNumero(tx, fallbackNumero);
            if (tx.getTransactionIndex() == null || tx.getTransactionIndex() <= 0) {
                fallbackNumero++;
            }

            if (isSplitTransaction(tx)) {
                rows.add(new SimulatedEntry(
                        numeroMain,
                        moisTexte,
                        nmoisTexte,
                        dateOperation,
                        journal,
                        contrepartie,
                        libelle,
                        debit,
                        credit,
                        tx.getId(),
                        false));
                continue;
            }

            if (cmExp != null && isCmAppliedEffective(tx, cmExp)) {
                appendCmAccountingRows(rows, tx, cmExp, numeroMain, moisTexte, nmoisTexte,
                        dateOperation, journal, bankAccount, libelle);
            } else {
                rows.add(new SimulatedEntry(numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                        contrepartie, libelle, debit, credit, tx.getId(), false));
                rows.add(new SimulatedEntry(numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                        bankAccount, libelle, credit, debit, tx.getId(), true));
            }
        }

        String simulationId = UUID.randomUUID().toString();
        simulations.put(simulationId, new SimulationContext(
                simulationId,
                statementId,
                journal,
                nmois,
                rows,
                LocalDateTime.now()));

        return new SimulationResult(simulationId, statementId, journal, nmois, rows);
    }

    @Transactional
    public ConfirmationResult confirm(String simulationId, String userId) {
        if (simulationId == null || simulationId.isBlank()) {
            throw new IllegalArgumentException("simulationId est obligatoire.");
        }

        SimulationContext context = simulations.get(simulationId);
        if (context == null) {
            throw new IllegalArgumentException("Simulation introuvable ou expiree. Relancez la simulation.");
        }

        Long contextStatementId = java.util.Objects.requireNonNull(context.statementId(), "statementId manquant dans la simulation");

        List<AccountingEntry> entries = new ArrayList<>(context.rows().size());
        List<CptjournalJdbcRepository.CptjournalRow> cptjournalRows = new ArrayList<>(context.rows().size());
        long cptjournalBaseNumero = cptjournalJdbcRepository.findMaxNumero() + 1;
        List<SimulatedEntry> rows = context.rows();
        for (int i = 0; i < rows.size(); i++) {
            SimulatedEntry row = rows.get(i);
            AccountingEntry entry = new AccountingEntry();
            entry.setNumero(row.numero());
            entry.setMois(row.moisTexte());
            entry.setNmois(context.nmois());
            entry.setDateComplete(row.dateOperation());
            entry.setDate(row.dateOperation().getDayOfMonth());
            entry.setEcriture(row.libelle());
            entry.setDebit(row.debit());
            entry.setCredit(row.credit());
            entry.setNdosjrn(context.journal());
            entry.setNcompte(row.ncompte());
            entry.setAccountNumber(row.ncompte());
            entry.setSourceStatementId(contextStatementId);
            entry.setSourceTransactionId(row.sourceTransactionId());
            entry.setIsCounterpart(row.counterpart());
            entry.setBatchId(simulationId);
            entries.add(entry);

            long numeroCptjournal = cptjournalBaseNumero + i;
            LocalDate dateOperation = row.dateOperation();
            cptjournalRows.add(new CptjournalJdbcRepository.CptjournalRow(
                    numeroCptjournal,
                    context.journal(),
                    context.nmois(),
                    row.moisTexte(),
                    row.ncompte(),
                    row.libelle(),
                    row.debit(),
                    row.credit(),
                    VALIDER_TRUE,
                    dateOperation,
                    dateOperation.getDayOfMonth(),
                    dateOperation.getYear(),
                    resolveMntRester(row.ncompte(), row.debit(), row.credit())));
        }
        accountingEntryRepository.saveAll(entries);
        if (!cptjournalSyncTrackerRepository.isSynced(contextStatementId)) {
            cptjournalJdbcRepository.insertAll(cptjournalRows);
            cptjournalSyncTrackerRepository.markSynced(contextStatementId);
        }

        BanqueReleve statement = bankStatementRepository.findById(contextStatementId)
                .orElseThrow(() -> new IllegalArgumentException("Releve introuvable."));
        String actor = (userId == null || userId.isBlank()) ? "system" : userId;
        if (statement.getStatus() == BanqueStatus.VALIDATED || statement.getStatus() == BanqueStatus.COMPTABILISE) {
            statement.markAsAccounted(actor);
        } else {
            statement.setStatus(BanqueStatus.COMPTABILISE);
            statement.setAccountedAt(LocalDateTime.now());
            statement.setAccountedBy(actor);
        }
        bankStatementRepository.save(statement);

        simulations.remove(simulationId);
        return new ConfirmationResult(
                simulationId,
                contextStatementId,
                entries.size(),
                statement.getStatus().name(),
                statement.getAccountedAt(),
                statement.getAccountedBy());
    }

    private boolean isSplitTransaction(BanqueTransaction tx) {
        if (tx == null) {
            return false;
        }
        String splitRole = tx.getFraisSplitRole();
        return splitRole != null && !splitRole.isBlank();
    }

    @Transactional
    public int syncCptjournalFromExistingAccountingEntries(Long statementId) {
        if (statementId == null) {
            throw new IllegalArgumentException("statementId est obligatoire.");
        }
        if (cptjournalSyncTrackerRepository.isSynced(statementId)) {
            return 0;
        }

        List<AccountingEntry> entries = accountingEntryRepository.findBySourceStatementIdOrderByNumeroAscIdAsc(statementId);
        if (entries.isEmpty()) {
            return 0;
        }

        long minNumero = entries.stream()
                .map(AccountingEntry::getNumero)
                .filter(n -> n != null)
                .min(Long::compareTo)
                .orElse(1L);
        long baseNumero = cptjournalJdbcRepository.findMaxNumero() + 1;

        List<CptjournalJdbcRepository.CptjournalRow> rows = new ArrayList<>(entries.size());
        for (AccountingEntry entry : entries) {
            LocalDate date = entry.getDateComplete();
            if (date == null) {
                continue;
            }
            long numero = baseNumero + (entry.getNumero() - minNumero);
            BigDecimal debit = entry.getDebit() == null ? BigDecimal.ZERO : entry.getDebit();
            BigDecimal credit = entry.getCredit() == null ? BigDecimal.ZERO : entry.getCredit();
            rows.add(new CptjournalJdbcRepository.CptjournalRow(
                    numero,
                    entry.getNdosjrn(),
                    entry.getNmois(),
                    entry.getMois(),
                    entry.getNcompte(),
                    entry.getEcriture(),
                    debit,
                    credit,
                    VALIDER_TRUE,
                    date,
                    date.getDayOfMonth(),
                    date.getYear(),
                    resolveMntRester(entry.getNcompte(), debit, credit)));
        }

        cptjournalJdbcRepository.insertAll(rows);
        cptjournalSyncTrackerRepository.markSynced(statementId);
        return rows.size();
    }

    private int resolveMonth(BanqueReleve statement, List<BanqueTransaction> transactions) {
        if (statement.getMonth() != null && statement.getMonth() >= 1 && statement.getMonth() <= 12) {
            return statement.getMonth();
        }
        for (BanqueTransaction tx : transactions) {
            if (tx.getDateOperation() != null) {
                return tx.getDateOperation().getMonthValue();
            }
        }
        return LocalDate.now().getMonthValue();
    }

    private String monthLabel(int nmois) {
        return Month.of(nmois).getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    private String sanitizeJournal(String journal) {
        if (journal == null || journal.isBlank()) {
            throw new IllegalArgumentException("Journal de comptabilisation manquant.");
        }
        return journal.trim();
    }

    private String sanitizeAccount(String account, String label) {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException(label + " manquant.");
        }
        String value = account.trim();
        if (!value.matches(ACCOUNT_CODE_REGEX)) {
            throw new IllegalArgumentException(label + " invalide (9 chiffres attendus).");
        }
        return value;
    }

    private String sanitizeTransactionAccount(String account) {
        if (account == null || account.isBlank()) {
            return DEFAULT_TX_COMPTE;
        }
        String value = account.trim();
        return value.matches(ACCOUNT_CODE_REGEX) ? value : DEFAULT_TX_COMPTE;
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private void appendCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            CmExpansionDTO cmExp,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle) {
        String structure = normalizeStructure(cmExp.cmBatchStructure());
        BigDecimal commissionHt = parseAmount(cmExp.commissionHt());
        BigDecimal tvaSurCommissions = parseAmount(cmExp.tvaSurCommissions());
        BigDecimal soldeNet = parseAmount(cmExp.cmMontant());
        if (soldeNet.compareTo(BigDecimal.ZERO) == 0) {
            soldeNet = tx.getCredit() != null && tx.getCredit().compareTo(BigDecimal.ZERO) > 0
                    ? tx.getCredit()
                    : tx.getDebit();
        }

        if ("AMEX".equals(structure)) {
            appendAmexCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                    bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
            return;
        }
        if ("VPS".equals(structure)) {
            appendVpsCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                    bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
            return;
        }
        if ("BARID_BANK".equals(structure)) {
            appendBaridCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                    bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
            return;
        }
        if ("CMI".equals(structure)) {
            appendCmiCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                    bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
            return;
        }
        appendDefaultCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
    }

    private void appendAmexCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle,
            BigDecimal soldeNet,
            BigDecimal commissionHt,
            BigDecimal tvaSurCommissions) {
        BigDecimal grossAmount = resolveGrossAmount(soldeNet, commissionHt, tvaSurCommissions);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TOTAL_REMISE_COMPTE, libelle, BigDecimal.ZERO, grossAmount, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                COMMISSION_COMPTE, libelle, commissionHt, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TVA_COMPTE, libelle, tvaSurCommissions, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, BigDecimal.ZERO, tx.getId(), true);
    }

    private void appendVpsCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle,
            BigDecimal soldeNet,
            BigDecimal commissionHt,
            BigDecimal tvaSurCommissions) {
        BigDecimal grossAmount = resolveGrossAmount(soldeNet, commissionHt, tvaSurCommissions);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TOTAL_REMISE_COMPTE, libelle, BigDecimal.ZERO, grossAmount, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                COMMISSION_COMPTE, libelle, commissionHt, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TVA_COMPTE, libelle, tvaSurCommissions, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, BigDecimal.ZERO, tx.getId(), true);
    }

    private void appendBaridCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle,
            BigDecimal soldeNet,
            BigDecimal commissionHt,
            BigDecimal tvaSurCommissions) {
        BigDecimal grossAmount = resolveGrossAmount(soldeNet, commissionHt, tvaSurCommissions);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TOTAL_REMISE_COMPTE, libelle, BigDecimal.ZERO, grossAmount, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                COMMISSION_COMPTE, libelle, commissionHt, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TVA_COMPTE, libelle, tvaSurCommissions, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, BigDecimal.ZERO, tx.getId(), true);
    }

    private void appendDefaultCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle,
            BigDecimal soldeNet,
            BigDecimal commissionHt,
            BigDecimal tvaSurCommissions) {
        BigDecimal grossAmount = resolveGrossAmount(soldeNet, commissionHt, tvaSurCommissions);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TOTAL_REMISE_COMPTE, libelle, BigDecimal.ZERO, grossAmount, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                COMMISSION_COMPTE, libelle, commissionHt, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                TVA_COMPTE, libelle, tvaSurCommissions, BigDecimal.ZERO, tx.getId(), false);
        appendCmRow(rows, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, BigDecimal.ZERO, tx.getId(), true);
    }

    private void appendCmiCmAccountingRows(
            List<SimulatedEntry> rows,
            BanqueTransaction tx,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String bankAccount,
            String libelle,
            BigDecimal soldeNet,
            BigDecimal commissionHt,
            BigDecimal tvaSurCommissions) {
        appendDefaultCmAccountingRows(rows, tx, numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                bankAccount, libelle, soldeNet, commissionHt, tvaSurCommissions);
    }

    private void appendCmRow(
            List<SimulatedEntry> rows,
            long numeroMain,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String account,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            Long sourceTransactionId,
            boolean counterpart) {
        BigDecimal safeDebit = debit == null ? BigDecimal.ZERO : debit;
        BigDecimal safeCredit = credit == null ? BigDecimal.ZERO : credit;
        if (safeDebit.compareTo(BigDecimal.ZERO) == 0 && safeCredit.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        rows.add(new SimulatedEntry(numeroMain, moisTexte, nmoisTexte, dateOperation, journal,
                account, libelle, safeDebit, safeCredit, sourceTransactionId, counterpart));
    }

    private boolean isCmAppliedEffective(BanqueTransaction tx, CmExpansionDTO cmExp) {
        if (tx == null || cmExp == null) {
            return false;
        }
        if (Boolean.TRUE.equals(tx.getCmAppliedUserDisabled())) {
            return false;
        }
        if (Boolean.TRUE.equals(tx.getCmApplied())) {
            return true;
        }
        return true;
    }

    private BigDecimal resolveGrossAmount(BigDecimal netAmount,
                                          BigDecimal commissionHt,
                                          BigDecimal tvaSurCommissions) {
        BigDecimal gross = netAmount == null ? BigDecimal.ZERO : netAmount;
        if (commissionHt != null) {
            gross = gross.add(commissionHt);
        }
        if (tvaSurCommissions != null) {
            gross = gross.add(tvaSurCommissions);
        }
        return gross;
    }

    private String normalizeStructure(String structure) {
        if (structure == null) {
            return "";
        }
        return structure.trim().toUpperCase();
    }

    private long resolveTransactionNumero(BanqueTransaction tx, long fallbackNumero) {
        if (tx.getTransactionIndex() != null && tx.getTransactionIndex() > 0) {
            return tx.getTransactionIndex().longValue();
        }
        return fallbackNumero;
    }

    private BigDecimal resolveMntRester(String ncompte, BigDecimal debit, BigDecimal credit) {
        if (ncompte == null) {
            return null;
        }
        String value = ncompte.trim();
        if (value.startsWith("4411")
                || value.startsWith("342")
                || value.startsWith("1481")
                || value.startsWith("1486")) {
            return debit.subtract(credit).abs();
        }
        return null;
    }

    private record SimulationContext(
            String simulationId,
            Long statementId,
            String journal,
            int nmois,
            List<SimulatedEntry> rows,
            LocalDateTime createdAt) {
    }

    public record SimulatedEntry(
            long numero,
            String moisTexte,
            String nmoisTexte,
            LocalDate dateOperation,
            String journal,
            String ncompte,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            Long sourceTransactionId,
            boolean counterpart) {
    }

    public record SimulationResult(
            String simulationId,
            Long statementId,
            String journal,
            int nmois,
            List<SimulatedEntry> entries) {
    }

    public record ConfirmationResult(
            String simulationId,
            Long statementId,
            int insertedEntries,
            String statementStatus,
            LocalDateTime accountedAt,
            String accountedBy) {
    }
}


