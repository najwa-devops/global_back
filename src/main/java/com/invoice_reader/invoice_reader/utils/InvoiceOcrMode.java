package com.invoice_reader.invoice_reader.utils;

public enum InvoiceOcrMode {
    OCR_SCAN,
    EVOLEO_AI;

    public static InvoiceOcrMode resolve(String rawMode, Boolean useAlphaAgent) {
        if (rawMode != null && !rawMode.isBlank()) {
            String normalized = rawMode.trim().toUpperCase()
                    .replace('-', '_')
                    .replace(' ', '_');

            if ("NORMAL_SCAN".equals(normalized) || "EVOLEO_SCAN".equals(normalized) || "OCR_SCAN".equals(normalized)) {
                return OCR_SCAN;
            }

            if ("ALPHA_ACTIVE".equals(normalized) || "ALPHA".equals(normalized) || "EVOLEO_AI".equals(normalized)) {
                return EVOLEO_AI;
            }
        }

        return Boolean.TRUE.equals(useAlphaAgent) ? EVOLEO_AI : OCR_SCAN;
    }
}
