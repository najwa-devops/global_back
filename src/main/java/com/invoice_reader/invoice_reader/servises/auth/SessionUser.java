package com.invoice_reader.invoice_reader.servises.auth;

import com.invoice_reader.invoice_reader.entity.auth.UserRole;

public record SessionUser(Long id, String username, UserRole role, String displayName) {
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isComptable() {
        return role == UserRole.COMPTABLE;
    }

    public boolean isClient() {
        return role == UserRole.CLIENT;
    }
}
