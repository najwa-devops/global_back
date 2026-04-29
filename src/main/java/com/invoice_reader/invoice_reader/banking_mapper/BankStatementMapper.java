package com.invoice_reader.invoice_reader.banking_mapper;

import com.invoice_reader.invoice_reader.banking_dto.BankStatementDTO;
import com.invoice_reader.invoice_reader.banking_dto.BankStatementDetailDTO;
import com.invoice_reader.invoice_reader.banking_entity.BankStatement;
import com.invoice_reader.invoice_reader.banking_entity.BankTransaction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Mapper pour BankStatement
 */
@Component
public class BankStatementMapper {

    private final BankTransactionMapper transactionMapper;

    public BankStatementMapper(BankTransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    /**
     * Convertit une entité en DTO simple
     */
    public BankStatementDTO toDTO(BankStatement entity) {
        if (entity == null) {
            return null;
        }

        return BankStatementDTO.builder()
                .id(entity.getId())
                .filename(entity.getFilename())
                .originalName(entity.getOriginalName())
                .rib(entity.getRib())
                .month(entity.getMonth())
                .year(entity.getYear())
                .bankName(entity.getBankName())
                .openingBalance(entity.getOpeningBalance())
                .closingBalance(entity.getClosingBalance())
                .totalCredit(entity.getTotalCredit())
                .totalDebit(entity.getTotalDebit())
                .totalCreditPdf(entity.getTotalCreditPdf())
                .totalDebitPdf(entity.getTotalDebitPdf())
                .balanceDifference(entity.getBalanceDifference())
                .verificationStatus(entity.getVerificationStatus())
                .status(entity.getStatus())
                .continuityStatus(entity.getContinuityStatus())
                .isBalanceValid(entity.getIsBalanceValid())
                .isContinuityValid(entity.getIsContinuityValid())
                .transactionCount(entity.getTransactionCount())
                .validTransactionCount(entity.getValidTransactionCount())
                .errorTransactionCount(entity.getErrorTransactionCount())
                .overallConfidence(entity.getOverallConfidence())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .validatedAt(entity.getValidatedAt())
                .fileSize(entity.getFileSize())
                .build();
    }

    /**
     * Convertit une entité en DTO détaillé (avec transactions)
     */
    public BankStatementDetailDTO toDetailDTO(BankStatement entity, boolean includeTransactions) {
        if (entity == null) {
            return null;
        }

        BankStatementDetailDTO dto = BankStatementDetailDTO.builder()
                .id(entity.getId())
                .filename(entity.getFilename())
                .originalName(entity.getOriginalName())
                .rib(entity.getRib())
                .month(entity.getMonth())
                .year(entity.getYear())
                .bankName(entity.getBankName())
                .accountHolder(entity.getAccountHolder())
                .openingBalance(entity.getOpeningBalance())
                .closingBalance(entity.getClosingBalance())
                .totalCredit(entity.getTotalCredit())
                .totalDebit(entity.getTotalDebit())
                .balanceDifference(entity.getBalanceDifference())
                .status(entity.getStatus())
                .continuityStatus(entity.getContinuityStatus())
                .isBalanceValid(entity.getIsBalanceValid())
                .isContinuityValid(entity.getIsContinuityValid())
                .validationErrors(entity.getValidationErrors())
                .transactionCount(entity.getTransactionCount())
                .validTransactionCount(entity.getValidTransactionCount())
                .errorTransactionCount(entity.getErrorTransactionCount())
                .overallConfidence(entity.getOverallConfidence())
                .filePath(entity.getFilePath())
                .fileSize(entity.getFileSize())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .validatedAt(entity.getValidatedAt())
                .validatedBy(entity.getValidatedBy())
                .rawOcrText(entity.getRawOcrText())
                .cleanedOcrText(entity.getCleanedOcrText())
                .fraisRuleAppliedCount(countAppliedFraisRules(entity))
                .hasFraisRuleApplied(countAppliedFraisRules(entity) > 0)
                .build();

        if (includeTransactions && entity.getTransactions() != null) {
            dto.setTransactions(
                    entity.getTransactions().stream()
                            .map(transactionMapper::toDTO)
                            .collect(Collectors.toList()));
        }

        return dto;
    }

    /**
     * Convertit une entité en DTO détaillé avec preview des transactions
     */
    public BankStatementDetailDTO toDetailDTOWithPreview(BankStatement entity, int previewCount) {
        if (entity == null) {
            return null;
        }

        BankStatementDetailDTO dto = toDetailDTO(entity, false);

        if (entity.getTransactions() != null && !entity.getTransactions().isEmpty()) {
            dto.setTransactions(
                    entity.getTransactions().stream()
                            .limit(previewCount)
                            .map(transactionMapper::toDTO)
                            .collect(Collectors.toList()));
            dto.setTransactionsPreviewCount(previewCount);
        }

        return dto;
    }

    private int countAppliedFraisRules(BankStatement entity) {
        if (entity.getTransactions() == null || entity.getTransactions().isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> grouped = new LinkedHashSet<>();
        int fallbackCount = 0;
        for (BankTransaction transaction : entity.getTransactions()) {
            if (!Boolean.TRUE.equals(transaction.getFraisRuleApplied())
                    || !isFraisSplitRole(transaction.getFraisSplitRole())) {
                continue;
            }
            String groupId = transaction.getFraisSplitGroupId();
            if (groupId != null && !groupId.isBlank()) {
                grouped.add(groupId.trim());
            } else {
                fallbackCount++;
            }
        }
        return grouped.size() + fallbackCount;
    }

    private boolean isFraisSplitRole(String splitRole) {
        return splitRole != null && splitRole.startsWith("FRAIS_");
    }
}
