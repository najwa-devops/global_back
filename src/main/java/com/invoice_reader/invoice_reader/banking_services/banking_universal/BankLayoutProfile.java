package com.invoice_reader.invoice_reader.banking_services.banking_universal;

import java.util.Set;

public interface BankLayoutProfile {
    Set<String> creditHints();

    Set<String> debitHints();

    Set<String> ignoredLineHints();
}

