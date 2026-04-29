package com.invoice_reader.invoice_reader.banking_repository;

import com.invoice_reader.invoice_reader.banking_entity.JournalBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface JournalBatchRepository extends JpaRepository<JournalBatch, Long> {

    Optional<JournalBatch> findByStatementId(Long statementId);

    @Modifying
    @Transactional
    @Query("DELETE FROM JournalBatch b WHERE b.statementId = :statementId")
    void deleteByStatementId(@Param("statementId") Long statementId);

    @Query("""
            SELECT DISTINCT b.year, b.month
            FROM JournalBatch b
            ORDER BY b.year DESC, b.month DESC
            """)
    List<Object[]> findDistinctPeriods();

    List<JournalBatch> findByYearAndMonthOrderByCreatedAtDesc(Integer year, Integer month);

    List<JournalBatch> findAllByOrderByYearDescMonthDescCreatedAtDesc();
}
