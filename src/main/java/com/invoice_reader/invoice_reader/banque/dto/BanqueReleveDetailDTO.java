package com.invoice_reader.invoice_reader.banque.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * DTO détaillé pour un relevé bancaire
 * (inclut les transactions)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BanqueReleveDetailDTO extends BanqueReleveDTO {

    private String accountHolder;
    private String validationErrors;
    private String filePath;
    private String validatedBy;

    // OCR (optionnel, non inclus par défaut)
    private String rawOcrText;
    private String cleanedOcrText;

    // Transactions (preview ou complet)
    private List<BanqueTransactionDTO> transactions;
    private Integer transactionsPreviewCount; // Nombre de transactions en preview
    private Integer fraisRuleAppliedCount;
    private Boolean hasFraisRuleApplied;
}
