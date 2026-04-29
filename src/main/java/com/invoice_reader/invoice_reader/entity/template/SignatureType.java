package com.invoice_reader.invoice_reader.entity.template;

public enum SignatureType {
    ICE, // Identifiant Commun de l'Entreprise (15 chiffres)
    IF, // Identifiant Fiscal (7-10 chiffres)
    RC, // Registre de Commerce
    SUPPLIER
}
