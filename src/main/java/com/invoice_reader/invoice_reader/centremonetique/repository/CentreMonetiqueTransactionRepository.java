package com.invoice_reader.invoice_reader.centremonetique.repository;

import com.invoice_reader.invoice_reader.centremonetique.entity.CentreMonetiqueTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreMonetiqueTransactionRepository extends JpaRepository<CentreMonetiqueTransaction, Long> {
    List<CentreMonetiqueTransaction> findByBatchIdOrderByRowIndexAsc(Long batchId);
    void deleteByBatchId(Long batchId);
}
