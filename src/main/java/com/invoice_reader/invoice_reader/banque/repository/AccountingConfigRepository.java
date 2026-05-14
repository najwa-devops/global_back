package com.invoice_reader.invoice_reader.banque.repository;

import com.invoice_reader.invoice_reader.banque.entity.AccountingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountingConfigRepository extends JpaRepository<AccountingConfig, Long> {

    @Query("select distinct c.banque from AccountingConfig c order by c.banque")
    List<String> findDistinctBanques();

    Optional<AccountingConfig> findFirstByRibOrderByIdDesc(String rib);
}
