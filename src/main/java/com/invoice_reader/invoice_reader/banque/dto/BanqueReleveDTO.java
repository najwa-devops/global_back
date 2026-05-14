package com.invoice_reader.invoice_reader.banque.dto;

import com.invoice_reader.invoice_reader.banque.entity.ContinuityStatus;
import com.invoice_reader.invoice_reader.banque.entity.BanqueStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO pour les relevés bancaires
 * (version légère pour les listes)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BanqueReleveDTO {

    private Long id;
    private String filename;
    private String originalName;
    private String rib;
    private Integer month;
    private Integer year;
    private String bankName;

    // Soldes
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal totalCredit;
    private BigDecimal totalDebit;
    private BigDecimal totalCreditPdf;
    private BigDecimal totalDebitPdf;
    private BigDecimal balanceDifference;
    private String verificationStatus;

    // Statuts
    private BanqueStatus status;
    private ContinuityStatus continuityStatus;
    private Boolean isBalanceValid;
    private Boolean isContinuityValid;

    // Statistiques
    private Integer transactionCount;
    private Integer validTransactionCount;
    private Integer errorTransactionCount;
    private Double overallConfidence;

    // Dates
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime validatedAt;

    // Taille fichier
    private Long fileSize;
}
