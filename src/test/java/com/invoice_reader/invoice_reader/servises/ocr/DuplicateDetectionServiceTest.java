package com.invoice_reader.invoice_reader.servises.ocr;

import com.invoice_reader.invoice_reader.entity.dynamic.DuplicateLevel;
import com.invoice_reader.invoice_reader.entity.dynamic.DynamicInvoice;
import com.invoice_reader.invoice_reader.repository.DynamicInvoiceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DuplicateDetectionServiceTest {

    @Mock
    private DynamicInvoiceDao dynamicInvoiceDao;

    @InjectMocks
    private DuplicateDetectionService service;

    private DynamicInvoice invoice;

    @BeforeEach
    void setUp() {
        invoice = new DynamicInvoice();
        invoice.setId(10L);
        invoice.setDossierId(1L);
    }

    // ── Stratégie 1 : Doublon CERTAIN ────────────────────────────────────────

    @Test
    void detect_sameNumberAndIce_CERTAIN() {
        invoice.setField("invoiceNumber", "F-2024-001");
        invoice.setField("ice", "001234567000012");

        when(dynamicInvoiceDao.findCertainDuplicate(1L, 10L, "F-2024-001", "001234567000012"))
                .thenReturn(Optional.of(5L));

        DuplicateDetectionService.DetectionResult result = service.detect(invoice);

        assertEquals(DuplicateLevel.CERTAIN, result.level());
        assertEquals(5L, result.duplicateOfId());
        verify(dynamicInvoiceDao).findCertainDuplicate(1L, 10L, "F-2024-001", "001234567000012");
    }

    // ── Stratégie 2 : Doublon PROBABLE ───────────────────────────────────────

    @Test
    void detect_sameTtcAndSupplierInDateWindow_PROBABLE() {
        // Pas de N° facture → stratégie 1 skippée
        invoice.setField("amountTTC", 1200.0);
        invoice.setField("supplier", "SARL ATLAS");
        invoice.setField("invoiceDate", "15/03/2024");

        when(dynamicInvoiceDao.findProbableDuplicate(
                eq(1L), eq(10L), eq(1200.0), eq("SARL ATLAS"), any(Date.class)))
                .thenReturn(Optional.of(7L));

        DuplicateDetectionService.DetectionResult result = service.detect(invoice);

        assertEquals(DuplicateLevel.PROBABLE, result.level());
        assertEquals(7L, result.duplicateOfId());
    }

    // ── Aucun doublon ─────────────────────────────────────────────────────────

    @Test
    void detect_noMatch_NONE() {
        invoice.setField("invoiceNumber", "F-2024-999");
        invoice.setField("ice", "001111111000011");
        invoice.setField("amountTTC", 500.0);
        invoice.setField("supplier", "NOUVEAU FOURNISSEUR");
        invoice.setField("invoiceDate", "01/01/2024");

        when(dynamicInvoiceDao.findCertainDuplicate(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dynamicInvoiceDao.findProbableDuplicate(anyLong(), anyLong(), anyDouble(), anyString(), any(Date.class)))
                .thenReturn(Optional.empty());

        DuplicateDetectionService.DetectionResult result = service.detect(invoice);

        assertEquals(DuplicateLevel.NONE, result.level());
        assertNull(result.duplicateOfId());
    }

    // ── Cas limites ───────────────────────────────────────────────────────────

    @Test
    void detect_nullId_returnsNONE() {
        invoice.setId(null);
        DuplicateDetectionService.DetectionResult result = service.detect(invoice);
        assertEquals(DuplicateLevel.NONE, result.level());
        verifyNoInteractions(dynamicInvoiceDao);
    }

    @Test
    void detect_nullDossierId_returnsNONE() {
        invoice.setDossierId(null);
        DuplicateDetectionService.DetectionResult result = service.detect(invoice);
        assertEquals(DuplicateLevel.NONE, result.level());
        verifyNoInteractions(dynamicInvoiceDao);
    }

    @Test
    void detect_unparsableDate_skipsStrategie2() {
        // Date invalide → stratégie 2 skippée silencieusement
        invoice.setField("amountTTC", 500.0);
        invoice.setField("supplier", "SARL TEST");
        invoice.setField("invoiceDate", "not-a-date");

        DuplicateDetectionService.DetectionResult result = service.detect(invoice);

        assertEquals(DuplicateLevel.NONE, result.level());
        verify(dynamicInvoiceDao, never()).findProbableDuplicate(anyLong(), anyLong(), anyDouble(), anyString(), any());
    }
}
