# Dossier `settings/`

Package : `com.invoice_reader.invoice_reader.settings`

Ce module gère les **paramètres généraux du dossier actif** : informations légales de l'entreprise, devise, taux TVA par défaut et période d'exercice comptable.

---

## Structure

```
settings/
├── controller/   ← Endpoint REST pour lire et mettre à jour les paramètres
└── dto/          ← Requête de création/mise à jour des paramètres
```

---

## settings/controller/

| Fichier | Routes | Rôle |
|---------|--------|------|
| `GeneralParamsController.java` | `GET /api/settings/general-params` · `PUT /api/settings/general-params` | Lecture et mise à jour des paramètres généraux du dossier actif : devise, taux TVA par défaut, ICE, IF, RC, adresse, raison sociale, période d'exercice |

---

## settings/dto/

| Fichier | Type | Rôle |
|---------|------|------|
| `UpsertDossierGeneralParamsRequest.java` | Requête | Création ou mise à jour des paramètres généraux : `raisonSociale`, `ice`, `if_`, `rc`, `adresse`, `devise`, `tauxTvaDefaut`, `exerciceDebut`, `exerciceFin` |

---

## Endpoints

| Méthode | Route | Contrôleur |
|---------|-------|------------|
| `GET` | `/api/settings/general-params` | `GeneralParamsController` |
| `PUT` | `/api/settings/general-params` | `GeneralParamsController` |
