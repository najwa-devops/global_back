package com.invoice_reader.invoice_reader.achat.service;

import java.util.Locale;

public enum ExtractionEngine {
    DEFAULT,
    ALPHA_AGENT;

    public static ExtractionEngine fromRequest(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT;
        }
        String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        if ("ALPHA".equals(normalized) || "ALPHA_AGENT".equals(normalized)) {
            return ALPHA_AGENT;
        }
        return DEFAULT;
    }

    public static ExtractionEngine resolve(String engine, String ocrMode, Boolean useAlphaAgent) {
        ExtractionEngine requested = fromRequest(engine);
        if (requested == ALPHA_AGENT) {
            return requested;
        }
        if (Boolean.TRUE.equals(useAlphaAgent)) {
            return ALPHA_AGENT;
        }
        if (ocrMode != null && !ocrMode.isBlank()) {
            String normalizedMode = ocrMode.trim().toUpperCase(Locale.ROOT);
            if ("EVOLEO_AI".equals(normalizedMode)
                    || "ALPHA".equals(normalizedMode)
                    || "ALPHA_AGENT".equals(normalizedMode)
                    || "ALPHA_ACTIVE".equals(normalizedMode)) {
                return ALPHA_AGENT;
            }
        }
        return requested;
    }
}
