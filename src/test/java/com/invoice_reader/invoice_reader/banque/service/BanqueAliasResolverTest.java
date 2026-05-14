package com.invoice_reader.invoice_reader.banque.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BanqueAliasResolverTest {

    @Test
    void normalizePolicyBankCode_mapsBanquePopulaireToBcp() {
        assertEquals("BCP", BanqueAliasResolver.normalizePolicyBankCode("Banque Populaire (BCP)"));
    }

    @Test
    void normalizePolicyBankCode_mapsCompositeBanquePopulaireLabelToBcp() {
        assertEquals("BCP", BanqueAliasResolver.normalizePolicyBankCode("BANQUE POPULAIRE BCP"));
    }

    @Test
    void normalizePolicyBankCode_mapsAutomaticDetectionToAuto() {
        assertEquals("AUTO", BanqueAliasResolver.normalizePolicyBankCode("Detection automatique"));
    }

    @Test
    void prioritizeAutoFirst_movesAutoToFirstPosition() {
        assertEquals(
                List.of("AUTO", "BCP", "BMCE"),
                BanqueAliasResolver.prioritizeAutoFirst(List.of("BCP", "AUTO", "BMCE")));
    }
}
