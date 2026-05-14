package com.invoice_reader.invoice_reader.security;

import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import com.invoice_reader.invoice_reader.auth.service.AuthService;
import com.invoice_reader.invoice_reader.auth.service.SessionUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionAuthInterceptorTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private SessionAuthInterceptor interceptor;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ── Endpoints publics ─────────────────────────────────────────────────────

    @Test
    void preHandle_requeteOptions_laissePasser() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/dynamic-invoices/upload");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        verifyNoInteractions(authService);
    }

    @Test
    void preHandle_endpointLogin_laissePasser() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/auth/login");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verifyNoInteractions(authService);
    }

    @Test
    void preHandle_endpointLogout_laissePasser() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/auth/logout");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verifyNoInteractions(authService);
    }

    // ── Session manquante ou invalide → 401 ───────────────────────────────────

    @Test
    void preHandle_sansSession_retourne401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/dynamic-invoices/list");
        // Pas de session attachée → getSession(false) retourne null

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(authService);
    }

    @Test
    void preHandle_sessionSansUtilisateurValide_retourne401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/dynamic-invoices/list");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        when(authService.requireSessionUser(session)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    // ── Session valide, pas d'annotation @RequireRole → laisse passer ─────────

    @Test
    void preHandle_sessionValide_sansAnnotationRequireRole_laissePasser() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/dynamic-invoices/list");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        SessionUser admin = new SessionUser(1L, "admin", UserRole.ADMIN, "Admin");
        when(authService.requireSessionUser(session)).thenReturn(admin);

        // On passe un Object simple (pas un HandlerMethod) → pas de vérification de rôle
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    // ── hasRole() — méthode interne testée via preHandle ─────────────────────

    @Test
    void preHandle_sessionAdmin_accesEndpointProtege_laissePasser() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/admin/users");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        SessionUser admin = new SessionUser(1L, "admin", UserRole.ADMIN, "Admin");
        when(authService.requireSessionUser(session)).thenReturn(admin);

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void preHandle_reponseUnauthorized_contientJsonErreur() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/dynamic-invoices/list");
        // Pas de session

        interceptor.preHandle(request, response, new Object());

        assertEquals(401, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertTrue(response.getContentAsString().contains("unauthorized"));
    }
}
