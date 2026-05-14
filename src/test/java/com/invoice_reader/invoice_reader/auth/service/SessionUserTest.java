package com.invoice_reader.invoice_reader.auth.service;

import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionUserTest {

    // ── isAdmin() ─────────────────────────────────────────────────────────────

    @Test
    void isAdmin_avecRoleAdmin_retourneTrue() {
        SessionUser user = new SessionUser(1L, "admin", UserRole.ADMIN, "Administrateur");
        assertTrue(user.isAdmin());
    }

    @Test
    void isAdmin_avecRoleComptable_retourneFalse() {
        SessionUser user = new SessionUser(2L, "jean", UserRole.COMPTABLE, "Jean");
        assertFalse(user.isAdmin());
    }

    @Test
    void isAdmin_avecRoleClient_retourneFalse() {
        SessionUser user = new SessionUser(3L, "client1", UserRole.CLIENT, "Client 1");
        assertFalse(user.isAdmin());
    }

    // ── isComptable() ─────────────────────────────────────────────────────────

    @Test
    void isComptable_avecRoleComptable_retourneTrue() {
        SessionUser user = new SessionUser(2L, "jean", UserRole.COMPTABLE, "Jean");
        assertTrue(user.isComptable());
    }

    @Test
    void isComptable_avecRoleAdmin_retourneFalse() {
        SessionUser user = new SessionUser(1L, "admin", UserRole.ADMIN, "Administrateur");
        assertFalse(user.isComptable());
    }

    // ── isClient() ────────────────────────────────────────────────────────────

    @Test
    void isClient_avecRoleClient_retourneTrue() {
        SessionUser user = new SessionUser(3L, "client1", UserRole.CLIENT, "Client 1");
        assertTrue(user.isClient());
    }

    @Test
    void isClient_avecRoleAdmin_retourneFalse() {
        SessionUser user = new SessionUser(1L, "admin", UserRole.ADMIN, "Admin");
        assertFalse(user.isClient());
    }

    // ── Exclusivité des rôles ─────────────────────────────────────────────────

    @Test
    void admin_estUniquementAdmin() {
        SessionUser user = new SessionUser(1L, "admin", UserRole.ADMIN, "Admin");
        assertTrue(user.isAdmin());
        assertFalse(user.isComptable());
        assertFalse(user.isClient());
    }

    @Test
    void comptable_estUniquementComptable() {
        SessionUser user = new SessionUser(2L, "jean", UserRole.COMPTABLE, "Jean");
        assertFalse(user.isAdmin());
        assertTrue(user.isComptable());
        assertFalse(user.isClient());
    }

    @Test
    void client_estUniquementClient() {
        SessionUser user = new SessionUser(3L, "client1", UserRole.CLIENT, "Client");
        assertFalse(user.isAdmin());
        assertFalse(user.isComptable());
        assertTrue(user.isClient());
    }
}
