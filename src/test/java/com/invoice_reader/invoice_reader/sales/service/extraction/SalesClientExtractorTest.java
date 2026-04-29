package com.invoice_reader.invoice_reader.sales.service.extraction;

import com.invoice_reader.invoice_reader.sales.utils.SalesExtractionPatterns;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesClientExtractorTest {

    @Test
    void cleanNumber_givenSpaces_whenClean_thenKeepOnlyDigits() {
        assertEquals("001234567890123", SalesExtractionPatterns.cleanNumber("00123 456 789 0123"));
    }

    @Test
    void isValidIce_givenFifteenDigits_whenValidate_thenReturnTrue() {
        assertTrue(SalesExtractionPatterns.isValidICE("001234567890123"));
    }

    @Test
    void isValidIce_givenWrongLength_whenValidate_thenReturnFalse() {
        assertFalse(SalesExtractionPatterns.isValidICE("1234567890"));
    }

    @Test
    void icePattern_givenInlineIce_whenMatch_thenExtractNumber() {
        String text = "Client X - ICE: 001234567890123";
        Pattern pattern = Pattern.compile(SalesExtractionPatterns.ICE_PATTERNS[0]);
        Matcher matcher = pattern.matcher(text);

        assertTrue(matcher.find());
        assertEquals("001234567890123", matcher.group(1));
    }
}
