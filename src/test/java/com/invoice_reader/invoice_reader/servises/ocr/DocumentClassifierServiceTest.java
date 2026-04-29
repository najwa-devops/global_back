package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.entity.dynamic.DocumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentClassifierServiceTest {

    private DocumentClassifierService service;

    @BeforeEach
    void setUp() {
        service = new DocumentClassifierService();
    }

    @Test
    void classify_avoir_returnsAVOIR() {
        String text = "AVOIR N° AV-2024-001\nAnnulation facture F-2024-099\nNOTE DE CRÉDIT";
        assertEquals(DocumentType.AVOIR, service.classify(text));
    }

    @Test
    void classify_bonLivraison_returnsBON_LIVRAISON() {
        String text = "BON DE LIVRAISON\nBL N° 2024-055\nLivraison client";
        assertEquals(DocumentType.BON_LIVRAISON, service.classify(text));
    }

    @Test
    void classify_facture_returnsFACTURE_ACHAT() {
        String text = "FACTURE N° F-2024-001\nFOURNISSEUR SARL\nNET À PAYER: 1200 DH";
        assertEquals(DocumentType.FACTURE_ACHAT, service.classify(text));
    }

    @Test
    void classify_empty_returnsUNKNOWN() {
        assertEquals(DocumentType.UNKNOWN, service.classify(""));
    }

    @Test
    void classify_null_returnsUNKNOWN() {
        assertEquals(DocumentType.UNKNOWN, service.classify(null));
    }

    @Test
    void classify_unrecognized_returnsUNKNOWN() {
        assertEquals(DocumentType.UNKNOWN, service.classify("Lorem ipsum dolor sit amet consectetur"));
    }

    @Test
    void classify_avoirPrioritizedOverFacture() {
        // Texte avec mots "FACTURE" et "AVOIR" : AVOIR doit gagner (score plus élevé)
        String text = "AVOIR\nFACTURE annulée\nANNULATION\nFOURNISSEUR";
        assertEquals(DocumentType.AVOIR, service.classify(text));
    }
}
