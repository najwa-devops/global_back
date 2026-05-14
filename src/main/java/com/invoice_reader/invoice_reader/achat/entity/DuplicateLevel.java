package com.invoice_reader.invoice_reader.achat.entity;

/**
 * Niveau de doublon détecté par le DuplicateDetectionService.
 * Placé dans entity/dynamic pour éviter une dépendance de l'entité vers la couche service.
 */
public enum DuplicateLevel {
    NONE,       // Pas de doublon détecté
    PROBABLE,   // Doublon probable (même TTC + même fournisseur + date ±7 jours)
    CERTAIN     // Doublon certain (même N° facture + même ICE dans le même dossier)
}
