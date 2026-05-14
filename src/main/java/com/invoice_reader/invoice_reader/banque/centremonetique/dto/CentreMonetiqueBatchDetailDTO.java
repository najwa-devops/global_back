package com.invoice_reader.invoice_reader.banque.centremonetique.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CentreMonetiqueBatchDetailDTO {
    private Long id;
    private String filename;
    private String originalName;
    private String rib;
    private String status;
    private String structure;
    private String statementPeriod;
    private String fileContentType;
    private Long fileSize;
    private String totalTransactions;
    private String totalMontant;
    private String totalCommissionHt;
    private String totalTvaSurCommissions;
    private String soldeNetRemise;
    private String totalDebit;
    private String totalCredit;
    private Integer transactionCount;
    private String createdAt;
    private String updatedAt;
    private String errorMessage;
    private String rawOcrText;
    private Boolean clientValidated;
    private String clientValidatedAt;
    private String clientValidatedBy;
    private List<CentreMonetiqueExtractionRow> rows;
}
