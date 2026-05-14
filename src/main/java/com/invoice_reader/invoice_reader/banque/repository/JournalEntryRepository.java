package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.banque.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    List<JournalEntry> findByBatchIdOrderByNumeroAscIdAsc(Long batchId);
    long countByBatchId(Long batchId);

    @Modifying
    @Transactional
    @Query("DELETE FROM JournalEntry e WHERE e.batch.id = :batchId")
    void deleteByBatchId(@Param("batchId") Long batchId);
}
