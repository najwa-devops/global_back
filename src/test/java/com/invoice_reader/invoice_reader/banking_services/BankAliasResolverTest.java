package com.invoice_reader.invoice_reader.banking_services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankAliasResolverTest {

    @Test
    void normalizePolicyBankCode_mapsBanquePopulaireToBcp() {
        assertEquals("BCP", BankAliasResolver.normalizePolicyBankCode("Banque Populaire (BCP)"));
    }

    @Test
    void normalizePolicyBankCode_mapsCompositeBanquePopulaireLabelToBcp() {
        assertEquals("BCP", BankAliasResolver.normalizePolicyBankCode("BANQUE POPULAIRE BCP"));
    }

    @Test
    void normalizePolicyBankCode_mapsAutomaticDetectionToAuto() {
        assertEquals("AUTO", BankAliasResolver.normalizePolicyBankCode("Detection automatique"));
    }

    @Test
    void prioritizeAutoFirst_movesAutoToFirstPosition() {
        assertEquals(
                List.of("AUTO", "BCP", "BMCE"),
                BankAliasResolver.prioritizeAutoFirst(List.of("BCP", "AUTO", "BMCE")));
    }
}
