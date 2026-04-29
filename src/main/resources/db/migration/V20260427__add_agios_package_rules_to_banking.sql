-- ============================================================================
-- MIGRATION : Ajout des règles AGIOS et PACKAGE sur les relevés bancaires
-- Date: 2026-04-27
-- Description: Ajoute deux drapeaux de règle au niveau bank_statement pour
--              activer/désactiver le traitement des libellés AGIOS et PACKAGE.
-- ============================================================================

ALTER TABLE bank_statement
    ADD COLUMN IF NOT EXISTS apply_agios_rule BOOLEAN NOT NULL DEFAULT FALSE AFTER apply_frais_rule,
    ADD COLUMN IF NOT EXISTS apply_package_rule BOOLEAN NOT NULL DEFAULT FALSE AFTER apply_agios_rule;

UPDATE bank_statement
SET apply_agios_rule = FALSE
WHERE apply_agios_rule IS NULL;

UPDATE bank_statement
SET apply_package_rule = FALSE
WHERE apply_package_rule IS NULL;

-- ============================================================================
-- FIN DE LA MIGRATION
-- ============================================================================
