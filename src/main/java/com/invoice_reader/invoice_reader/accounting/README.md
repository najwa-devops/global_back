# Dossier `accounting/`

Package : `com.invoice_reader.invoice_reader.accounting`

Ce module expose le **journal comptable transversal** qui agrège les écritures issues des factures d'achat (`achat/`) et de vente (`vente/`). Il s'appuie sur les entités de `database/entity/accounting/`.

---

## Structure

```
accounting/
└── controller/   ← Endpoint REST du journal comptable
```

---

## accounting/controller/

| Fichier | Routes | Rôle |
|---------|--------|------|
| `AccountingJournalController.java` | `GET /api/accounting/journal/entries` · `GET /api/accounting/journal/defaults` · `POST /api/accounting/journal/entries/from-invoice/{id}` · `POST /api/accounting/journal/entries/rebuild/{id}` | Journal comptable : consultation des écritures, récupération des comptes par défaut, génération d'une écriture depuis une facture, reconstruction d'une écriture |

---

## Endpoints

| Méthode | Route | Description |
|---------|-------|-------------|
| `GET` | `/api/accounting/journal/entries` | Liste toutes les écritures du journal |
| `GET` | `/api/accounting/journal/defaults` | Retourne les comptes comptables par défaut du dossier |
| `POST` | `/api/accounting/journal/entries/from-invoice/{id}` | Génère une écriture comptable depuis une facture existante |
| `POST` | `/api/accounting/journal/entries/rebuild/{id}` | Reconstruit / recalcule une écriture comptable existante |

---

## Dépendances

- `database/entity/accounting/` — entités `AccountingEntry` (écritures) et `JournalBatch` (lots)
- `banque/` — les relevés bancaires génèrent aussi des écritures via `ComptabilisationWorkflowService`
- `achat/` et `vente/` — les factures sont la source principale des écritures
