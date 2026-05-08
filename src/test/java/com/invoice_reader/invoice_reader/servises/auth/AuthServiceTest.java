package com.invoice_reader.invoice_reader.servises.auth;

import com.invoice_reader.invoice_reader.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.repository.UserAccountDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserAccountDao userAccountDao;

    @InjectMocks
    private AuthService authService;

    private UserAccount activeAdmin;

    @BeforeEach
    void setUp() {
        activeAdmin = new UserAccount();
        activeAdmin.setId(1L);
        activeAdmin.setUsername("admin");
        activeAdmin.setPassword("secret");
        activeAdmin.setRole(UserRole.ADMIN);
        activeAdmin.setActive(true);
    }

    // ── authenticate() ────────────────────────────────────────────────────────

    @Test
    void authenticate_usernameNull_retourneVide() {
        assertTrue(authService.authenticate(null, "pass").isEmpty());
        verifyNoInteractions(userAccountDao);
    }

    @Test
    void authenticate_passwordNull_retourneVide() {
        assertTrue(authService.authenticate("admin", null).isEmpty());
        verifyNoInteractions(userAccountDao);
    }

    @Test
    void authenticate_utilisateurInexistant_retourneVide() {
        when(userAccountDao.findByUsername("inconnu")).thenReturn(Optional.empty());
        assertTrue(authService.authenticate("inconnu", "pass").isEmpty());
    }

    @Test
    void authenticate_compteDesactive_retourneVide() {
        activeAdmin.setActive(false);
        when(userAccountDao.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));
        assertTrue(authService.authenticate("admin", "secret").isEmpty());
    }

    @Test
    void authenticate_mauvaisMotDePasse_retourneVide() {
        when(userAccountDao.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));
        assertTrue(authService.authenticate("admin", "mauvais").isEmpty());
    }

    @Test
    void authenticate_identifiantsCorrects_retourneUtilisateur() {
        when(userAccountDao.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));
        Optional<UserAccount> result = authService.authenticate("admin", "secret");
        assertTrue(result.isPresent());
        assertEquals("admin", result.get().getUsername());
        assertEquals(UserRole.ADMIN, result.get().getRole());
    }

    @Test
    void authenticate_roleNull_retourneVide() {
        activeAdmin.setRole(null);
        when(userAccountDao.findByUsername("admin")).thenReturn(Optional.of(activeAdmin));
        assertTrue(authService.authenticate("admin", "secret").isEmpty());
    }

    // ── requireSessionUser() ─────────────────────────────────────────────────

    @Test
    void requireSessionUser_sessionVide_retourneNull() {
        MockHttpSession session = new MockHttpSession();
        assertNull(authService.requireSessionUser(session));
    }

    @Test
    void requireSessionUser_userIdManquant_retourneNull() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USERNAME, "admin");
        session.setAttribute(SessionKeys.ROLE, "ADMIN");
        assertNull(authService.requireSessionUser(session));
    }

    @Test
    void requireSessionUser_roleManquant_retourneNull() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USER_ID, 1L);
        session.setAttribute(SessionKeys.USERNAME, "admin");
        assertNull(authService.requireSessionUser(session));
    }

    @Test
    void requireSessionUser_roleInvalide_retourneNull() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USER_ID, 1L);
        session.setAttribute(SessionKeys.USERNAME, "admin");
        session.setAttribute(SessionKeys.ROLE, "ROLE_INEXISTANT");
        assertNull(authService.requireSessionUser(session));
    }

    @Test
    void requireSessionUser_sessionAdmin_retourneSessionUserCorrect() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USER_ID, 1L);
        session.setAttribute(SessionKeys.USERNAME, "admin");
        session.setAttribute(SessionKeys.ROLE, "ADMIN");
        session.setAttribute(SessionKeys.DISPLAY_NAME, "Administrateur");

        SessionUser result = authService.requireSessionUser(session);

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("admin", result.username());
        assertEquals(UserRole.ADMIN, result.role());
        assertEquals("Administrateur", result.displayName());
    }

    @Test
    void requireSessionUser_sessionComptable_retourneRoleComptable() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USER_ID, 2L);
        session.setAttribute(SessionKeys.USERNAME, "jean");
        session.setAttribute(SessionKeys.ROLE, "COMPTABLE");

        SessionUser result = authService.requireSessionUser(session);

        assertNotNull(result);
        assertEquals(UserRole.COMPTABLE, result.role());
        assertTrue(result.isComptable());
    }

    @Test
    void requireSessionUser_sessionClient_retourneRoleClient() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.USER_ID, 3L);
        session.setAttribute(SessionKeys.USERNAME, "client1");
        session.setAttribute(SessionKeys.ROLE, "CLIENT");

        SessionUser result = authService.requireSessionUser(session);

        assertNotNull(result);
        assertEquals(UserRole.CLIENT, result.role());
        assertTrue(result.isClient());
    }
}
