package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransactionAccountRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BanqueTransactionAccountRuleRepository extends JpaRepository<BanqueTransactionAccountRule, Long> {

    Optional<BanqueTransactionAccountRule> findByNormalizedLibelle(String normalizedLibelle);

    List<BanqueTransactionAccountRule> findTop200ByOrderByUsageCountDescUpdatedAtDesc();
}
