package com.invoice_reader.invoice_reader.banque.service;
import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BanqueAliasResolver {

    private static final Map<String, BanqueType> TOKEN_TO_TYPE = Map.ofEntries(
            Map.entry("BCP", BanqueType.BCP),
            Map.entry("BANQUE POPULAIRE", BanqueType.BCP),
            Map.entry("BANK AL YOUSR", BanqueType.BCP),
            Map.entry("AL YOUSR", BanqueType.BCP),

            Map.entry("CREDIT DU MAROC", BanqueType.CREDIT_DU_MAROC),
            Map.entry("ARREDA", BanqueType.CREDIT_DU_MAROC),

            Map.entry("BMCI", BanqueType.BMCI),
            Map.entry("NAJMAH", BanqueType.BMCI),

            Map.entry("CIH", BanqueType.CIH),
            Map.entry("CIH BANK", BanqueType.CIH),
            Map.entry("UMNIA", BanqueType.CIH),
            Map.entry("UMNIA BANK", BanqueType.CIH),

            Map.entry("CREDIT AGRICOLE", BanqueType.CREDIT_AGRICOLE),
            Map.entry("AL AKHDAR BANK", BanqueType.CREDIT_AGRICOLE),
            Map.entry("AL AKHDAR", BanqueType.CREDIT_AGRICOLE),

            Map.entry("SOCIETE GENERALE", BanqueType.SOCIETE_GENERALE),
            Map.entry("SG MAROC", BanqueType.SOCIETE_GENERALE),
            Map.entry("SGMB", BanqueType.SOCIETE_GENERALE),
            Map.entry("SAHAM BANK", BanqueType.SOCIETE_GENERALE),
            Map.entry("SAHAM", BanqueType.SOCIETE_GENERALE),
            Map.entry("DAR AL AMANE", BanqueType.SOCIETE_GENERALE),
            Map.entry("DAR AL-AMANE", BanqueType.SOCIETE_GENERALE),

            Map.entry("AL BARID", BanqueType.BARID_BANK),
            Map.entry("BARID BANK", BanqueType.BARID_BANK),
            Map.entry("AL BARID BANK", BanqueType.BARID_BANK),

            Map.entry("BMCE", BanqueType.BMCE),
            Map.entry("BANK OF AFRICA", BanqueType.BMCE),
            Map.entry("BTI", BanqueType.BMCE),
            Map.entry("BTI BANK", BanqueType.BMCE),

            Map.entry("ATTIJARIWAFA", BanqueType.ATTIJARIWAFA),
            Map.entry("ATTIJARIWAFA BANK", BanqueType.ATTIJARIWAFA),
            Map.entry("AWB", BanqueType.ATTIJARIWAFA),
            Map.entry("BANK ASSAFA", BanqueType.ATTIJARIWAFA),
            Map.entry("ASSAFA", BanqueType.ATTIJARIWAFA));

    private BanqueAliasResolver() {
    }

    public static BanqueType resolveType(String token) {
        String normalized = normalizeToken(token);
        if (normalized.isEmpty()) {
            return null;
        }
        BanqueType direct = TOKEN_TO_TYPE.get(normalized);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, BanqueType> entry : TOKEN_TO_TYPE.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static String normalizeAllowedBankCode(String token) {
        String normalized = normalizeToken(token);
        if (normalized.isEmpty()) {
            return "";
        }
        if (isAutoToken(normalized)) {
            return "AUTO";
        }

        BanqueType resolved = resolveType(token);
        if (resolved != null) {
            return resolved.name();
        }
        return normalized.replace(' ', '_');
    }

    public static List<Map<String, String>> bankChoices() {
        return List.of(
                choice("AUTO", "Détection automatique", "AUTO"),
                choice(BanqueType.ATTIJARIWAFA.name(), "Attijariwafa", BanqueType.ATTIJARIWAFA.name()),
                choice(BanqueType.BCP.name(), "Banque Populaire (BCP)", BanqueType.BCP.name()),
                choice(BanqueType.BMCE.name(), "Bank of Africa (BMCE)", BanqueType.BMCE.name()),
                choice(BanqueType.CIH.name(), "CIH Bank", BanqueType.CIH.name()),
                choice(BanqueType.CREDIT_DU_MAROC.name(), "Crédit du Maroc", BanqueType.CREDIT_DU_MAROC.name()),
                choice(BanqueType.BMCI.name(), "BMCI", BanqueType.BMCI.name()),
                choice(BanqueType.SOCIETE_GENERALE.name(), "Société Générale", BanqueType.SOCIETE_GENERALE.name()),
                choice(BanqueType.CREDIT_AGRICOLE.name(), "Crédit Agricole", BanqueType.CREDIT_AGRICOLE.name()),
                choice(BanqueType.BARID_BANK.name(), "Al Barid Bank", BanqueType.BARID_BANK.name()),

                choice("UMNIA_BANK", "Umnia Bank (participative)", BanqueType.CIH.name()),
                choice("BANK_ASSAFA", "Bank Assafa (participative)", BanqueType.ATTIJARIWAFA.name()),
                choice("BTI_BANK", "BTI Bank (participative)", BanqueType.BMCE.name()),
                choice("BANK_AL_YOUSR", "Bank Al Yousr (participative)", BanqueType.BCP.name()),
                choice("AL_AKHDAR_BANK", "Al Akhdar Bank (participative)", BanqueType.CREDIT_AGRICOLE.name()),
                choice("NAJMAH", "Najmah (participative)", BanqueType.BMCI.name()),
                choice("ARREDA", "Arreda (participative)", BanqueType.CREDIT_DU_MAROC.name()),
                choice("SAHAM_BANK", "Saham Bank", BanqueType.SOCIETE_GENERALE.name()),
                choice("DAR_AL_AMANE", "Dar Al-Amane (participative)", BanqueType.SOCIETE_GENERALE.name()));
    }

    private static Map<String, String> choice(String code, String label, String mappedTo) {
        return Map.of(
                "code", code,
                "label", label,
                "mappedTo", mappedTo);
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        normalized = normalized.toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s{2,}", " ");
        return normalized;
    }

    private static boolean isAutoToken(String normalizedToken) {
        return "AUTO".equals(normalizedToken)
                || "AUTOMATIQUE".equals(normalizedToken)
                || "AUTOMATIC".equals(normalizedToken)
                || "DETECTION AUTO".equals(normalizedToken)
                || "DETECTION AUTOMATIQUE".equals(normalizedToken)
                || "DETECTION AUTOMATIC".equals(normalizedToken)
                || (normalizedToken.contains("DETECTION") && normalizedToken.contains("AUTO"));
    }

    public static String normalizePolicyBankCode(String token) {
        return normalizeAllowedBankCode(token);
    }

    public static List<String> prioritizeAutoFirst(List<String> codes) {
        if (codes == null || !codes.contains("AUTO")) {
            return codes;
        }
        List<String> result = new java.util.ArrayList<>(codes);
        result.remove("AUTO");
        result.add(0, "AUTO");
        return result;
    }
}
