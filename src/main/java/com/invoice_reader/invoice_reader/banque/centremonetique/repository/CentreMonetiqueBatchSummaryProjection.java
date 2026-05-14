package com.invoice_reader.invoice_reader.banque.centremonetique.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface CentreMonetiqueBatchSummaryProjection {
    Long getId();

    String getFilename();

    String getOriginalName();

    String getRib();

    String getStatus();

    String getStructure();

    String getStatementPeriod();

    BigDecimal getTotalMontant();

    BigDecimal getTotalCommissionHt();

    BigDecimal getTotalTvaSurCommissions();

    BigDecimal getSoldeNetRemise();

    BigDecimal getTotalDebit();

    BigDecimal getTotalCredit();

    Integer getTransactionCount();

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();

    String getErrorMessage();

    Boolean getClientValidated();

    LocalDateTime getClientValidatedAt();

    String getClientValidatedBy();
}
