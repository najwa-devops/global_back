package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.database.entity.accounting.AccountingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountingEntryDao extends JpaRepository<AccountingEntry, Long> {
    List<AccountingEntry> findByDossierIdOrderByEntryDateDescCreatedAtDesc(Long dossierId);
    List<AccountingEntry> findByInvoiceIdOrderByIdAsc(Long invoiceId);
    long countBySourceStatementId(Long sourceStatementId);
    List<AccountingEntry> findBySourceStatementIdOrderByNumeroAscIdAsc(Long sourceStatementId);
    long deleteByInvoiceId(Long invoiceId);

    @Query("""
            SELECT COALESCE(MAX(e.numero), 0)
            FROM AccountingEntry e
            WHERE e.ndosjrn = :journal
              AND e.nmois = :nmois
            """)
    long findMaxNumeroByJournalAndMonth(@Param("journal") String journal, @Param("nmois") Integer nmois);
}
