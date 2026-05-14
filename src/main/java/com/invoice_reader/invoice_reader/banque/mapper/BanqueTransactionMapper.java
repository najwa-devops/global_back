package com.invoice_reader.invoice_reader.banque.mapper;

import com.invoice_reader.invoice_reader.banque.dto.BanqueTransactionDTO;
import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;
import org.springframework.stereotype.Component;

/**
 * Mapper pour BanqueTransaction
 */
@Component
public class BanqueTransactionMapper {

    /**
     * Convertit une entité en DTO
     */
    public BanqueTransactionDTO toDTO(BanqueTransaction entity) {
        if (entity == null) {
            return null;
        }

        return BanqueTransactionDTO.builder()
                .id(entity.getId())
                .statementId(entity.getStatement() != null ? entity.getStatement().getId() : null)
                .dateOperation(entity.getDateOperation())
                .dateValeur(entity.getDateValeur())
                .libelle(entity.getLibelle())
                .rib(entity.getRib())
                .reference(entity.getReference())
                .code(entity.getCode())
                .debit(entity.getDebit())
                .credit(entity.getCredit())
                .sens(entity.getSens())
                .compte(entity.getCompte())
                .isLinked(entity.getIsLinked())
                .cmApplied(entity.getCmApplied())
                .fraisRuleApplied(entity.getFraisRuleApplied())
                .fraisSplitRole(entity.getFraisSplitRole())
                .fraisOriginalAmount(entity.getFraisOriginalAmount())
                .categorie(entity.getCategorie())
                .role(entity.getRole())
                .extractionConfidence(entity.getExtractionConfidence())
                .isValid(entity.getIsValid())
                .needsReview(entity.getNeedsReview())
                .extractionErrors(entity.getExtractionErrors())
                .reviewNotes(entity.getReviewNotes())
                .lineNumber(entity.getLineNumber())
                .transactionIndex(entity.getTransactionIndex())
                .build();
    }
}
