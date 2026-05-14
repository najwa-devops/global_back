# API — Module `database`

Package racine : `com.invoice_reader.invoice_reader.database`

Ce module contient la couche base de données **partagée et transversale** : entités JPA core, DAOs, et migrations de schéma. Les couches DB spécifiques aux modules métier restent dans leurs propres modules (`bank/entity`, `bank/repository`, `sales/entity`, `sales/repository`, etc.).

---

## Structure

```
database/
├── dao/            ← DAOs JPA pour les entités core (accès données)
├── entity/         ← Entités JPA partagées par tous les modules
│   ├── accounting/ ← Écritures comptables
│   ├── account_tier/ ← Comptes et niveaux d'abonnement
│   ├── auth/       ← Utilisateurs, dossiers, rôles
│   ├── dynamic/    ← Factures dynamiques, templates, apprentissage
│   ├── invoice/    ← Statuts factures (partagé)
│   └── template/   ← Signatures de templates
└── migration/      ← Migrations Java DDL/DML (schéma + seeds)
```

---

## database/migration

Migrations Java idempotentes exécutées au démarrage (`CommandLineRunner` ou `@PostConstruct`). Chaque migration vérifie l'existence des colonnes/tables avant d'agir.

| Classe | Ordre | Rôle |
|--------|-------|------|
| `AccountSchemaMigration` | 1 | Crée/met à jour la table `account` (comptes clients) avec toutes ses colonnes |
| `TierSchemaMigration` | 2 | Crée/met à jour la table `tier` (niveaux d'abonnement : quotas, limites) |
| `GeneralParamsSchemaMigration` | 3 | Crée/met à jour la table `dossier_general_params` (paramètres généraux par dossier) |
| `AccountSeedMigration` | 4 | Insère les comptes par défaut si la table est vide |
| `AdminUserSeedMigration` | 5 | Crée le compte administrateur initial (`admin@admin.com`) si absent |
| `DefaultDossierSeedMigration` | 6 | Crée un dossier par défaut pour les environnements de dev/test |

> **Migrations SQL Flyway** (dans `src/main/resources/db/migration/`) : scripts SQL versionnés pour les évolutions incrémentales en production. Nommage : `V{date}__{description}.sql`.

---

## database/dao

DAOs Spring Data JPA pour les entités core. Chaque DAO étend `JpaRepository` ou `CrudRepository`.

| Classe | Entité cible | Méthodes notables |
|--------|-------------|-------------------|
| `AccountDao` | `Account` | `findByEmail`, `findByDossierId`, `existsByEmail` |
| `TierDao` | `Tier` | `findByName`, `findDefault` |
| `UserAccountDao` | `UserAccount` | `findByUsername`, `findByDossierId`, `existsByUsername` |
| `DossierDao` | `Dossier` | `findByCode`, `findAllActive` |
| `DossierGeneralParamsDao` | `DossierGeneralParams` | `findByDossierId` |
| `DynamicInvoiceDao` | `DynamicInvoice` | `findByDossierAndStatus`, `findByNumeroFacture`, `countByDossierAndDateBetween` |
| `DynamicTemplateDao` | `DynamicTemplate` | `findByDossierIdAndActive`, `findBySignature` |
| `FieldLearningDataDao` | `FieldLearningData` | `findByDossierAndFieldName`, `findTopNByConfidence` |
| `FieldPatternDao` | `FieldPattern` | `findByTemplateAndFieldName`, `findActiveByDossier` |

---

## database/entity/auth

Entités d'authentification et de gestion des dossiers.

| Classe | Table | Rôle |
|--------|-------|------|
| `UserAccount` | `user_account` | Utilisateur du système : login, mot de passe hashé, rôle, dossier associé |
| `UserRole` | — (enum) | Enum des rôles : `ADMIN`, `USER`, `VIEWER` |
| `Dossier` | `dossier` | Dossier client (espace de travail isolé) : code, nom, abonnement, paramètres actifs |
| `DossierGeneralParams` | `dossier_general_params` | Paramètres généraux d'un dossier : devise, taux TVA par défaut, ICE, RC, IF |

---

## database/entity/account_tier

Entités de gestion des comptes et niveaux d'abonnement.

| Classe | Table | Rôle |
|--------|-------|------|
| `Account` | `account` | Compte client : informations de facturation, email, dossier lié, tier actif |
| `Tier` | `tier` | Niveau d'abonnement : nom, quotas (nb factures/mois, nb dossiers, stockage), prix |

---

## database/entity/accounting

Entités de comptabilisation partagées.

| Classe | Table | Rôle |
|--------|-------|------|
| `AccountingEntry` | `accounting_entry` | Écriture comptable générée depuis une facture ou transaction bancaire : compte débit, compte crédit, montant, date, libellé, référence source |

---

## database/entity/dynamic

Entités du module de traitement dynamique des factures (apprentissage par template).

| Classe | Table | Rôle |
|--------|-------|------|
| `DynamicInvoice` | `dynamic_invoice` | Facture extraite dynamiquement : tous les champs OCR (ICE, IF, RC, montants, dates), statut, confiance, dossier, template utilisé |
| `DynamicTemplate` | `dynamic_template` | Template de reconnaissance de facture : signature d'identification, champs configurés, fournisseur associé |
| `FieldLearningData` | `field_learning_data` | Donnée d'apprentissage : association entre un nom de champ et sa valeur corrigée manuellement par l'utilisateur |
| `FieldPattern` | `field_pattern` | Pattern d'extraction d'un champ : regex ou règle positionnelle liée à un template |
| `DetectionMethod` | — (enum) | Méthode de détection du template : `BY_HEADER`, `BY_ICE`, `BY_KEYWORD`, `MANUAL` |
| `DocumentType` | — (enum) | Type de document OCR : `INVOICE`, `BANK_STATEMENT`, `RECEIPT`, `CREDIT_NOTE`, `UNKNOWN` |
| `DuplicateLevel` | — (enum) | Niveau de détection de doublon : `NONE`, `PROBABLE`, `CERTAIN` |
| `FieldType` | — (enum) | Type d'un champ extrait : `TEXT`, `NUMBER`, `DATE`, `AMOUNT`, `ICE`, `RIB` |
| `LearningStatus` | — (enum) | Statut d'apprentissage d'un champ : `PENDING`, `VALIDATED`, `REJECTED` |

---

## database/entity/invoice

Types et convertisseurs partagés entre les modules facture.

| Classe | Rôle |
|--------|------|
| `InvoiceStatus` | Enum du statut d'une facture : `UPLOADED`, `PROCESSING`, `PROCESSED`, `VALIDATED`, `COMPTABILISE`, `ERROR` |
| `InvoiceStatusConverter` | `AttributeConverter` JPA : sérialise/désérialise `InvoiceStatus` en base (stocké comme `VARCHAR`) |

---

## database/entity/template

Entités de gestion des signatures de templates.

| Classe | Table | Rôle |
|--------|-------|------|
| `TemplateSignature` | `template_signature` | Signature unique d'identification d'un template de facture (hash des éléments structurels) |
| `SignatureType` | — (enum) | Type de signature : `HEADER_HASH`, `ICE_BASED`, `KEYWORD_BASED`, `LAYOUT_BASED` |

---

## Couches DB des autres modules (hors `database/`)

Ces entités et DAOs ne sont PAS dans ce module — ils appartiennent à leur contexte métier :

| Module | Localisation | Contenu |
|--------|-------------|---------|
| Bank | `bank/entity/`, `bank/repository/`, `bank/config/` | BankStatement, BankTransaction, JournalBatch, JournalEntry, AccountingConfig + tous leurs DAOs et migrations |
| Centre Monétique | `bank/centremonetique/entity/`, `.../repository/`, `.../config/` | CentreMonetiqueBatch, CentreMonetiqueTransaction + repo + migration |
| Sales | `sales/entity/`, `sales/repository/` | SalesInvoice, SalesInvoiceStatus + SalesInvoiceRepository |
| OCR | `ocr/config/` | OcrSchemaMigration (colonnes OCR sur dynamic_invoice et sales_invoice) |

---

## Schéma des tables core

```
user_account ──────→ dossier ──────→ dossier_general_params
                        │
                        ├──→ account ──→ tier
                        │
                        ├──→ dynamic_invoice ──→ dynamic_template
                        │       │                       │
                        │       └──→ field_learning_data └──→ field_pattern
                        │
                        └──→ accounting_entry
```

---

## Configuration JPA / DataSource

La configuration JPA (datasource, dialecte Hibernate, DDL) est dans `src/main/resources/application.properties` :
- `spring.datasource.url` — URL MariaDB
- `spring.jpa.hibernate.ddl-auto=update` — Hibernate gère la création/mise à jour des tables
- `spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect`

Les migrations Java de ce module complètent Hibernate pour les colonnes que JPA ne peut pas gérer seul (ajouts de colonnes sur tables existantes, seeds de données).
