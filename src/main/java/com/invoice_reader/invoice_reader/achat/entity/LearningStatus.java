package com.invoice_reader.invoice_reader.achat.entity;

public enum LearningStatus {

    PENDING,        // En attente de validation
    APPROVED,       // Validé par un admin
    REJECTED,       // Rejeté
    AUTO_APPROVED   // Auto-approuvé (haute confiance)

}
