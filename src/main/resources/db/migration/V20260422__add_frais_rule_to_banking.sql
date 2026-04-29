-- ============================================================================
-- MIGRATION : Ajout de la règle frais et des champs de split bancaire
-- Date: 2026-04-22
-- Description: Ajoute la colonne apply_frais_rule sur bank_statement et les
--              colonnes de traçage des splits sur bank_transaction.
-- ============================================================================

ALTER TABLE bank_statement
    ADD COLUMN IF NOT EXISTS apply_frais_rule BOOLEAN NOT NULL DEFAULT TRUE AFTER apply_ttc_rule;

UPDATE bank_statement
SET apply_frais_rule = TRUE
WHERE apply_frais_rule IS NULL;

ALTER TABLE bank_transaction
    ADD COLUMN IF NOT EXISTS frais_rule_applied BOOLEAN NOT NULL DEFAULT FALSE AFTER cm_applied,
    ADD COLUMN IF NOT EXISTS frais_split_group_id VARCHAR(64) NULL AFTER frais_rule_applied,
    ADD COLUMN IF NOT EXISTS frais_split_role VARCHAR(30) NULL AFTER frais_split_group_id,
    ADD COLUMN IF NOT EXISTS frais_original_amount DECIMAL(15,2) NULL AFTER frais_split_role;

UPDATE bank_transaction
SET frais_rule_applied = FALSE
WHERE frais_rule_applied IS NULL;

-- ============================================================================
-- FIN DE LA MIGRATION
-- ============================================================================
