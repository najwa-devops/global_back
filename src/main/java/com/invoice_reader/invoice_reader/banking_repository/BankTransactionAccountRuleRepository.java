package com.invoice_reader.invoice_reader.banking_repository;

import com.invoice_reader.invoice_reader.banking_entity.BankTransactionAccountRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankTransactionAccountRuleRepository extends JpaRepository<BankTransactionAccountRule, Long> {

    Optional<BankTransactionAccountRule> findByNormalizedLibelle(String normalizedLibelle);

    List<BankTransactionAccountRule> findTop200ByOrderByUsageCountDescUpdatedAtDesc();
}
