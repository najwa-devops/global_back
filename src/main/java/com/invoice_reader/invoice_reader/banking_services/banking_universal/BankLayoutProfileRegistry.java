package com.invoice_reader.invoice_reader.banking_services.banking_universal;

import com.invoice_reader.invoice_reader.banking_services.BankType;

public interface BankLayoutProfileRegistry {
    BankLayoutProfile getProfile(BankType bankType);
}

