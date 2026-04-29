package com.invoice_reader.invoice_reader.entity.dynamic;

/**
 * Type de document détecté par le DocumentClassifierService.
 * Utilisé pour classifier automatiquement les documents uploadés.
 */
public enum DocumentType {
    FACTURE_ACHAT,      // Facture fournisseur (défaut pour les factures)
    FACTURE_VENTE,      // Facture émise vers un client
    AVOIR,              // Note de crédit / avoir (sync avec DynamicInvoice.isAvoir)
    BON_LIVRAISON,      // Bon de livraison
    DEVIS,              // Devis / offre de prix
    RECU,               // Reçu / ticket
    UNKNOWN             // Type non identifié
}
