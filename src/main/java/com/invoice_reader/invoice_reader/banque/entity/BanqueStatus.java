package com.invoice_reader.invoice_reader.banque.entity;

import java.text.Normalizer;

public enum BanqueStatus {

    PENDING,
    PROCESSING,
    TREATED,
    READY_TO_VALIDATE,
    VALIDATED,
    COMPTABILISE,
    ERROR,
    VIDE,
    DUPLIQUE;

    public static BanqueStatus fromExternalValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase()
                .replace(' ', '_');

        return switch (normalized) {
            case "EN_ATTENTE" -> PENDING;
            case "EN_COURS" -> PROCESSING;
            case "A_VERIFIER" -> TREATED;
            case "PRET_A_VALIDER" -> READY_TO_VALIDATE;
            case "VALIDE" -> VALIDATED;
            case "ACCOUNTED" -> COMPTABILISE;
            case "ERREUR" -> ERROR;
            case "VIDE" -> VIDE;
            case "DUPLIQUE" -> DUPLIQUE;
            default -> {
                try {
                    yield BanqueStatus.valueOf(normalized);
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }
}
