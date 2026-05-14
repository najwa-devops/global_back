package com.invoice_reader.invoice_reader.banque.service.universal;

import com.invoice_reader.invoice_reader.banque.entity.BanqueTransaction;

import java.util.List;

public interface BalanceDrivenResolver {
    void resolve(List<BanqueTransaction> transactions);
}

