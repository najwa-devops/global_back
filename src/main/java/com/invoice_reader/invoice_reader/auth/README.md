# Dossier `auth/`

Package : `com.invoice_reader.invoice_reader.auth`

Ce module gère l'**authentification**, la gestion des **utilisateurs**, des **dossiers** (espaces de travail clients), et le **dashboard client**. Il ne dépend d'aucun module métier.

---

## Structure

```
auth/
├── controller/   ← Endpoints REST d'authentification, utilisateurs, dossiers, dashboard
├── dto/          ← Requêtes et réponses liées à l'authentification
└── service/      ← Logique métier d'authentification et de session
```

---

## auth/controller/

| Fichier | Routes | Rôle |
|---------|--------|------|
| `AuthController.java` | `POST /api/auth/login` · `POST /api/auth/logout` · `GET /api/auth/me` | Connexion (création de session), déconnexion (destruction de session), consultation de l'utilisateur connecté |
| `DossierController.java` | `POST /api/dossiers` · `GET /api/dossiers` · `POST /api/dossiers/active` | Création et listage des dossiers (espaces de travail) ; activation du dossier courant en session |
| `UserAdminController.java` | `POST /api/auth/users` · `GET /api/auth/users` · `DELETE /api/auth/users/{id}` | Administration des comptes utilisateurs : création, listage, suppression. Réservé aux `ADMIN` |
| `ClientDashboardController.java` | `GET /api/client/dashboard` · `GET /api/client/dashboard/buying-invoices` · `GET /api/client/dashboard/sales-invoices` | Dashboard client : statistiques et résumés des factures d'achat et de vente du dossier actif |

---

## auth/dto/

| Fichier | Type | Rôle |
|---------|------|------|
| `LoginRequest.java` | Requête | Identifiants de connexion : `username`, `password` |
| `CreateUserRequest.java` | Requête | Création d'un utilisateur : `username`, `password`, `role`, `displayName`, `dossierId` |
| `CreateDossierRequest.java` | Requête | Création d'un dossier : `code`, `nom`, `tierId` |
| `UpdateUserActiveRequest.java` | Requête | Activation/désactivation d'un compte utilisateur : `active` (booléen) |
| `UpdateUserRoleRequest.java` | Requête | Changement du rôle d'un utilisateur : `role` (`UserRole`) |
| `UserResponse.java` | Réponse | Données publiques d'un utilisateur : `id`, `username`, `role`, `displayName`, `active` (sans mot de passe) |

---

## auth/service/

| Fichier | Rôle |
|---------|------|
| `AuthService.java` | Service d'authentification : vérification des identifiants, création/destruction de session, récupération de l'utilisateur connecté |
| `SessionKeys.java` | Constantes des clés de session HTTP : `USER_ID`, `USERNAME`, `ROLE`, `DISPLAY_NAME`, `ACTIVE_DOSSIER_ID` |
| `SessionUser.java` | `record` représentant l'utilisateur connecté en session : `id`, `username`, `role`, `displayName`. Expose `isAdmin()`, `isComptable()`, `isClient()` |

---

## Endpoints

| Méthode | Route | Contrôleur |
|---------|-------|------------|
| `POST` | `/api/auth/login` | `AuthController` |
| `POST` | `/api/auth/logout` | `AuthController` |
| `GET` | `/api/auth/me` | `AuthController` |
| `POST` | `/api/dossiers` | `DossierController` |
| `GET` | `/api/dossiers` | `DossierController` |
| `POST` | `/api/dossiers/active` | `DossierController` |
| `POST` | `/api/auth/users` | `UserAdminController` |
| `GET` | `/api/auth/users` | `UserAdminController` |
| `DELETE` | `/api/auth/users/{id}` | `UserAdminController` |
| `GET` | `/api/client/dashboard` | `ClientDashboardController` |
| `GET` | `/api/client/dashboard/buying-invoices` | `ClientDashboardController` |
| `GET` | `/api/client/dashboard/sales-invoices` | `ClientDashboardController` |
