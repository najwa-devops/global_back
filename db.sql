-- MariaDB schema for the current Invoice Reader project
-- Generated from JPA entities in src/main/java/com/invoice_reader/invoice_reader/entity

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS field_learning_data;
DROP TABLE IF EXISTS accounting_entries;
DROP TABLE IF EXISTS dynamic_invoice;
DROP TABLE IF EXISTS dossiers;
DROP TABLE IF EXISTS dossier_general_params;
DROP TABLE IF EXISTS dynamic_template;
DROP TABLE IF EXISTS field_patterns;
DROP TABLE IF EXISTS tiers;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS user_accounts;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL,
    libelle VARCHAR(200) NOT NULL,
    classe INT NOT NULL,
    tva_rate DOUBLE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    created_by VARCHAR(100) NULL,
    updated_by VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_account_code (code),
    KEY idx_account_classe (classe),
    KEY idx_account_libelle (libelle),
    KEY idx_account_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    display_name VARCHAR(200) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_user_accounts_username (username),
    KEY idx_user_accounts_role (role),
    KEY idx_user_accounts_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dossiers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NULL,
    client_id BIGINT NOT NULL,
    comptable_id BIGINT NOT NULL,
    default_purchase_journal VARCHAR(50) NULL,
    default_sales_journal VARCHAR(50) NULL,
    exercise_start_date DATE NULL,
    exercise_end_date DATE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_dossiers_client (client_id),
    KEY idx_dossiers_comptable (comptable_id),
    CONSTRAINT fk_dossiers_client
        FOREIGN KEY (client_id) REFERENCES user_accounts(id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_dossiers_comptable
        FOREIGN KEY (comptable_id) REFERENCES user_accounts(id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dossier_general_params (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dossier_id BIGINT NOT NULL,
    company_name VARCHAR(200) NULL,
    address TEXT NULL,
    legal_form VARCHAR(150) NULL,
    rc_number VARCHAR(80) NULL,
    if_number VARCHAR(80) NULL,
    tsc VARCHAR(80) NULL,
    activity VARCHAR(255) NULL,
    category VARCHAR(255) NULL,
    professional_tax VARCHAR(80) NULL,
    ice VARCHAR(80) NULL,
    cni_or_residence_card VARCHAR(120) NULL,
    legal_representative VARCHAR(200) NULL,
    cm_rate DECIMAL(10,2) NULL,
    is_rate DECIMAL(10,2) NULL,
    capital DECIMAL(18,2) NULL,
    subject_to_ras BOOLEAN NULL DEFAULT FALSE,
    individual_person BOOLEAN NULL DEFAULT FALSE,
    has_fiscal_regularity_certificate BOOLEAN NULL DEFAULT FALSE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dossier_general_params_dossier (dossier_id),
    CONSTRAINT fk_dossier_general_params_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossiers(id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tiers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dossier_id BIGINT NOT NULL,
    auxiliaire_mode BOOLEAN NOT NULL DEFAULT FALSE,
    tier_number VARCHAR(31) NOT NULL,
    collectif_account VARCHAR(9) NULL,
    libelle VARCHAR(200) NOT NULL,
    activity VARCHAR(255) NULL,
    if_number VARCHAR(10) NULL,
    ice VARCHAR(15) NULL,
    rc_number VARCHAR(20) NULL,
    default_charge_account VARCHAR(9) NULL,
    tva_account VARCHAR(9) NULL,
    default_tva_rate DOUBLE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NULL,
    updated_by VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tier_dossier_if_number (dossier_id, if_number),
    UNIQUE KEY uk_tier_dossier_ice (dossier_id, ice),
    UNIQUE KEY uk_tier_dossier_tier_number (dossier_id, tier_number),
    KEY idx_tier_dossier (dossier_id),
    KEY idx_tier_libelle (libelle),
    KEY idx_tier_active (active),
    KEY idx_tier_auxiliaire (auxiliaire_mode),
    CONSTRAINT fk_tiers_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossiers(id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE field_patterns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    field_name VARCHAR(255) NOT NULL,
    pattern_regex VARCHAR(500) NOT NULL,
    priority INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(200) NULL,
    PRIMARY KEY (id),
    KEY idx_field_patterns_field_name (field_name),
    KEY idx_field_patterns_active (active),
    KEY idx_field_patterns_field_active_priority (field_name, active, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dynamic_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_name VARCHAR(255) NOT NULL,
    supplier_type VARCHAR(255) NOT NULL,
    signature_type VARCHAR(255) NOT NULL,
    signature_value VARCHAR(255) NOT NULL,
    field_definitions JSON NULL,
    fixed_supplier_data JSON NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    usage_count INT NULL DEFAULT 0,
    success_count INT NULL DEFAULT 0,
    last_used_at DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    created_by VARCHAR(255) NULL,
    tier_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_dynamic_template_signature (signature_type, signature_value),
    KEY idx_dynamic_template_supplier_type (supplier_type),
    KEY idx_dynamic_template_active (active),
    KEY idx_dynamic_template_tier (tier_id),
    CONSTRAINT fk_dynamic_template_tier
        FOREIGN KEY (tier_id) REFERENCES tiers(id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_dynamic_template_signature_type
        CHECK (signature_type IN ('ICE', 'IF', 'RC', 'SUPPLIER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE dynamic_invoice (
    id BIGINT NOT NULL AUTO_INCREMENT,
    filename VARCHAR(255) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(255) NULL,
    file_size BIGINT NULL,
    raw_ocr_text LONGTEXT NULL,
    extracted_data JSON NULL,
    fields_data JSON NULL,
    missing_fields JSON NULL,
    low_confidence_fields JSON NULL,
    auto_filled_fields JSON NULL,
    signature_type VARCHAR(255) NULL,
    signature_value VARCHAR(255) NULL,
    template_id BIGINT NULL,
    template_name VARCHAR(255) NULL,
    overall_confidence DOUBLE NULL,
    tier_id BIGINT NULL,
    tier_name VARCHAR(200) NULL,
    dossier_id BIGINT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    client_validated TINYINT(1) NULL,
    client_validated_at DATETIME(6) NULL,
    client_validated_by VARCHAR(255) NULL,
    validated_at DATETIME(6) NULL,
    validated_by VARCHAR(255) NULL,
    accounted TINYINT(1) NOT NULL DEFAULT 0,
    accounted_at DATETIME(6) NULL,
    accounted_by VARCHAR(255) NULL,
    learning_snapshot_id BIGINT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_dynamic_invoice_status (status),
    KEY idx_dynamic_invoice_template_id (template_id),
    KEY idx_dynamic_invoice_created_at (created_at),
    KEY idx_dynamic_invoice_tier (tier_id),
    KEY idx_dynamic_invoice_dossier (dossier_id),
    CONSTRAINT fk_dynamic_invoice_tier
        FOREIGN KEY (tier_id) REFERENCES tiers(id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_dynamic_invoice_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossiers(id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_dynamic_invoice_signature_type
        CHECK (signature_type IS NULL OR signature_type IN ('ICE', 'IF', 'RC', 'SUPPLIER')),
    CONSTRAINT chk_dynamic_invoice_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'TREATED', 'READY_TO_VALIDATE', 'VALIDATED', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE accounting_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dossier_id BIGINT NULL,
    invoice_id BIGINT NULL,
    invoice_number VARCHAR(100) NULL,
    supplier_name VARCHAR(200) NULL,
    journal VARCHAR(50) NULL,
    account_number VARCHAR(50) NOT NULL,
    entry_date DATE NULL,
    debit_amount DECIMAL(15,4) NULL,
    credit_amount DECIMAL(15,4) NULL,
    label VARCHAR(200) NULL,
    created_at DATETIME(6) NULL,
    created_by VARCHAR(100) NULL,
    PRIMARY KEY (id),
    KEY idx_accounting_entries_dossier (dossier_id),
    KEY idx_accounting_entries_invoice (invoice_id),
    KEY idx_accounting_entries_date (entry_date),
    KEY idx_accounting_entries_account (account_number),
    CONSTRAINT fk_accounting_entries_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossiers(id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT fk_accounting_entries_invoice
        FOREIGN KEY (invoice_id) REFERENCES dynamic_invoice(id)
        ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE field_learning_data (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    supplier_ice VARCHAR(15) NULL,
    supplier_if VARCHAR(10) NULL,
    supplier_name VARCHAR(200) NULL,
    field_name VARCHAR(50) NOT NULL,
    field_value TEXT NULL,
    detected_pattern VARCHAR(200) NULL,
    pattern_position JSON NULL,
    value_position JSON NULL,
    document_zone VARCHAR(20) NULL,
    pattern_value_distance DOUBLE NULL,
    relative_angle DOUBLE NULL,
    confidence_score DOUBLE NULL DEFAULT 0.5,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    applied_to_template_id BIGINT NULL,
    occurrence_count INT NULL DEFAULT 1,
    pattern_hash VARCHAR(64) NULL,
    ocr_context TEXT NULL,
    detection_method VARCHAR(30) NULL DEFAULT 'USER_SELECTION',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    created_by VARCHAR(100) NULL,
    approved_by VARCHAR(100) NULL,
    approved_at DATETIME(6) NULL,
    rejection_reason TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_field_learning_invoice_id (invoice_id),
    KEY idx_field_learning_status (status),
    KEY idx_field_learning_supplier_ice (supplier_ice),
    KEY idx_field_learning_supplier_if (supplier_if),
    KEY idx_field_learning_field_name (field_name),
    KEY idx_field_learning_applied_template (applied_to_template_id),
    KEY idx_field_learning_created_at (created_at),
    KEY idx_field_learning_pattern_hash (pattern_hash),
    KEY idx_field_learning_field_supplier_status (field_name, supplier_ice, status),
    CONSTRAINT fk_field_learning_invoice
        FOREIGN KEY (invoice_id) REFERENCES dynamic_invoice(id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_field_learning_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'AUTO_APPROVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
