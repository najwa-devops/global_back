package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueType;

public interface BanqueLayoutProfileRegistry {
    BanqueLayoutProfile getProfile(BanqueType bankType);
}

