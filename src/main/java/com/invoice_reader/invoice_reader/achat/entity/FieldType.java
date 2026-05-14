package com.invoice_reader.invoice_reader.achat.entity;

/**
 * Types de champs supportés pour l'extraction dynamique
 */
public enum FieldType {
    TEXT,       // Texte libre (numéro facture, fournisseur, etc.)
    NUMBER,     // Valeur numérique (montants, quantités)
    DATE,       // Date (formats variés)
    CURRENCY,   // Montant avec devise (DH, MAD, €)
    IDENTIFIER  // Identifiant structuré (ICE, IF, RC)
}
