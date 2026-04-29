package com.invoice_reader.invoice_reader.banking_dto;
import lombok.Data;

/**
 * DTO pour la modification d'une transaction
 */
@Data
public class BankTransactionUpdateRequest {
    private String compte;
    private Boolean isLinked;
    private Boolean cmApplied;
    private String categorie;
    private String libelle;
}
