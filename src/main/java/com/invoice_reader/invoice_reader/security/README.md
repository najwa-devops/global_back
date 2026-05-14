# Dossier `security/`

Package : `com.invoice_reader.invoice_reader.security`

Ce dossier contient la **couche de sécurité** de l'application : contrôle d'accès basé sur les rôles et interception des requêtes HTTP pour vérifier l'authentification par session.

---

## Fichiers

| Fichier | Rôle |
|---------|------|
| `RequireRole.java` | Annotation personnalisée `@RequireRole(UserRole[])` — placée sur un controller ou une méthode, elle déclare le(s) rôle(s) autorisés à accéder à cet endpoint. Utilisée par `SessionAuthInterceptor` pour le contrôle d'accès |
| `SessionAuthInterceptor.java` | `HandlerInterceptor` Spring enregistré dans `WebConfig` — intercepte chaque requête HTTP entrante, vérifie la présence d'une session utilisateur valide, lit l'annotation `@RequireRole` de la méthode cible et rejette (`403`) les requêtes dont le rôle est insuffisant. Gère également les routes publiques (login, logout) qui ne nécessitent pas d'authentification |

---

## Fonctionnement

```
Requête HTTP
      ↓
SessionAuthInterceptor.preHandle()
      ├─ Route publique (/api/auth/login, /api/auth/logout) → laisse passer
      ├─ Session absente → 401 Unauthorized
      ├─ @RequireRole présente → vérifie UserRole de la session
      │   ├─ Rôle insuffisant → 403 Forbidden
      │   └─ Rôle OK → laisse passer
      └─ Pas de @RequireRole → laisse passer (authentifié suffit)
```

## Rôles disponibles (`UserRole`)

Définis dans `database/entity/auth/UserRole.java` :

| Rôle | Accès |
|------|-------|
| `ADMIN` | Accès total : gestion des utilisateurs, dossiers, configuration |
| `COMPTABLE` | Accès aux factures, comptabilisation, relevés bancaires |
| `CLIENT` | Accès lecture seule à son dossier et ses factures |
