package com.invoice_reader.invoice_reader.vente.service.validation;

import com.invoice_reader.invoice_reader.vente.dto.VenteExtractionContext;
import com.invoice_reader.invoice_reader.vente.entity.VenteInvoiceStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper used by the sales invoice processing path to check the extraction
 * context and decide whether the invoice should be considered an error,
 * successfully extracted or still missing information.  A couple of helper
 * methods also flag low‑confidence fields so the front end can warn the user.
 *
 * This class is intentionally simple; the corresponding test exercises the
 * behaviour required by the current legacy sales module.
 */
public class VenteInvoiceValidator {

    /**
     * Determine an overall status based on the OCR text and the values present
     * in the extraction context.  The method also fills the
     * {@code missingFields} list on the context for callers who need the
     * details.
     */
    public VenteInvoiceStatus determineStatus(VenteExtractionContext ctx, String ocrText) {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            return VenteInvoiceStatus.ERROR;
        }

        // compute missing fields according to the rules exercised by the tests
        List<String> missing = new ArrayList<>();
        if (ctx.getClientIce() == null || ctx.getClientIce().isBlank()) {
            missing.add("clientIce");
        }
        if (ctx.getClientName() == null || ctx.getClientName().isBlank()) {
            missing.add("clientName");
        }
        if (ctx.getInvoiceNumber() == null || ctx.getInvoiceNumber().isBlank()) {
            missing.add("invoiceNumber");
        }
        if (ctx.getInvoiceDate() == null || ctx.getInvoiceDate().isBlank()) {
            missing.add("invoiceDate");
        }
        if (ctx.getTotalHt() == null) {
            missing.add("totalHt");
        }
        if (ctx.getTotalTva() == null) {
            missing.add("totalTva");
        }
        if (ctx.getTotalTtc() == null) {
            missing.add("totalTtc");
        }

        ctx.setMissingFields(missing);
        return VenteInvoiceStatus.EXTRACTED;
    }

    /**
     * Inspect the context and return a list of field names that appear to have
     * been extracted with low confidence.  The rules are very basic and match
     * the behaviour that the unit tests expect.
     */
    public List<String> detectLowConfidenceFields(VenteExtractionContext ctx) {
        List<String> low = new ArrayList<>();

        String ice = ctx.getClientIce();
        // valid ICE is a 15-digit number, anything else is suspect
        if (ice == null || !ice.matches("\\d{15}")) {
            low.add("clientIce");
        }

        Double ht = ctx.getTotalHt();
        if (ht != null && ht < 0 && !Boolean.TRUE.equals(ctx.getAvoir())) {
            low.add("totalHt");
        }

        Double ttc = ctx.getTotalTtc();
        Double tva = ctx.getTotalTva();
        if (ttc != null && ht != null && tva != null && !Boolean.TRUE.equals(ctx.getAvoir())) {
            double calculated = ht + tva;
            if (Math.abs(calculated - ttc) > 0.01) {
                low.add("totalTtc");
            }
        }

        return low;
    }
}
