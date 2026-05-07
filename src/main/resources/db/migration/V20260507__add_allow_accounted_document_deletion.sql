ALTER TABLE dossier_general_params
    ADD COLUMN IF NOT EXISTS allow_accounted_document_deletion BOOLEAN NOT NULL DEFAULT FALSE;
