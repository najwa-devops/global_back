package com.invoice_reader.invoice_reader.banque.service.universal;

import java.util.Set;

public interface BanqueLayoutProfile {
    Set<String> creditHints();

    Set<String> debitHints();

    Set<String> ignoredLineHints();
}

