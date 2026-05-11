package com.invoice_reader.invoice_reader.banking_repository;

import com.invoice_reader.invoice_reader.entity.accounting.AccountingEntry;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, Long> {

    long countBySourceStatementId(Long sourceStatementId);
    java.util.List<AccountingEntry> findBySourceStatementIdOrderByNumeroAscIdAsc(Long sourceStatementId);

    @Modifying
    @Transactional
    @Query("DELETE FROM AccountingEntry e WHERE e.sourceStatementId IS NOT NULL")
    void deleteAllBankEntries();

    @Query("""
            SELECT COALESCE(MAX(e.numero), 0)
            FROM AccountingEntry e
            WHERE e.ndosjrn = :journal
              AND e.nmois = :nmois
            """)
    long findMaxNumeroByJournalAndMonth(@Param("journal") String journal, @Param("nmois") Integer nmois);
}

