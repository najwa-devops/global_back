-- Optional migration: dossier-scoped general parameters
-- Safe to run manually even when spring.jpa.hibernate.ddl-auto=update is enabled.

CREATE TABLE IF NOT EXISTS dossier_general_params (
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
    allow_validated_document_deletion BOOLEAN NULL DEFAULT FALSE,
    allow_accounted_document_deletion BOOLEAN NULL DEFAULT FALSE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dossier_general_params_dossier (dossier_id),
    CONSTRAINT fk_dossier_general_params_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossiers(id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

