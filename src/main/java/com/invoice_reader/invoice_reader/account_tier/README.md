# Dossier `account_tier/`

Package : `com.invoice_reader.invoice_reader.account_tier`

Ce module gère les **comptes clients** (abonnements) et les **niveaux d'abonnement** (tiers), indépendamment de tout module métier.

---

## Structure

```
account_tier/
├── controller/   ← Endpoints REST pour comptes et tiers
├── dto/          ← Requêtes et réponses pour comptes et tiers
└── service/      ← Logique métier de gestion des comptes et tiers
```

---

## account_tier/controller/

| Fichier | Routes | Rôle |
|---------|--------|------|
| `AccountController.java` | `POST /api/accounting/accounts` · `GET /api/accounting/accounts/{id}` · `GET /api/accounting/accounts/by-code/{code}` | CRUD des comptes clients : création, consultation par identifiant ou code |
| `TierController.java` | `POST /api/accounting/tiers` · `GET /api/accounting/tiers/{id}` · `GET /api/accounting/tiers/by-tier-number/{n}` | CRUD des niveaux d'abonnement : création, consultation par identifiant ou numéro de tier |

---

## account_tier/dto/

| Fichier | Type | Rôle |
|---------|------|------|
| `AccountDto.java` | Réponse | Données d'un compte client : `id`, `email`, `dossierId`, `tierId`, `tierName` |
| `CreateAccountRequest.java` | Requête | Création d'un compte : `email`, `dossierId`, `tierId` |
| `UpdateAccountRequest.java` | Requête | Mise à jour d'un compte : champs modifiables (email, tier) |
| `TierDto.java` | Réponse | Données d'un niveau d'abonnement : `id`, `name`, `maxInvoices`, `maxDossiers`, `storageGB`, `price` |
| `CreateTierRequest.java` | Requête | Création d'un tier : nom, quotas (nb factures/mois, stockage), prix |
| `UpdateTierRequest.java` | Requête | Mise à jour d'un tier : champs modifiables |

---

## account_tier/service/

| Fichier | Rôle |
|---------|------|
| `AccountService.java` | Création, mise à jour, consultation des comptes clients ; association à un `Tier` |
| `TierService.java` | CRUD des tiers (nom, quotas, prix) ; vérification des limites d'utilisation d'un dossier |

---

## Endpoints

| Méthode | Route | Contrôleur |
|---------|-------|------------|
| `POST` | `/api/accounting/accounts` | `AccountController` |
| `GET` | `/api/accounting/accounts/{id}` | `AccountController` |
| `GET` | `/api/accounting/accounts/by-code/{code}` | `AccountController` |
| `POST` | `/api/accounting/tiers` | `TierController` |
| `GET` | `/api/accounting/tiers/{id}` | `TierController` |
| `GET` | `/api/accounting/tiers/by-tier-number/{n}` | `TierController` |
