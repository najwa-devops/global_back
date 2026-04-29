-- ============================================================================
-- MIGRATION : Ajout de l'autorisation par dossier pour Banque et Centre Monétique
-- Date: 2026-03-25
-- Description: Ajoute dossier_id aux tables cm_batch pour isolation des données
-- ============================================================================

-- 1. S'assurer qu'il y a au moins un dossier (pour les données existantes)
-- Si aucun dossier n'existe, on crée un dossier par défaut
INSERT INTO dossiers (name, company_name, company_ice, company_if, company_rc, created_at)
SELECT 'Dossier par défaut', 'Entreprise', '', '', '', NOW()
WHERE NOT EXISTS (SELECT 1 FROM dossiers LIMIT 1);

-- 2. Récupérer l'ID du premier dossier pour les données existantes
SET @default_dossier_id = (SELECT id FROM dossiers LIMIT 1);

-- 3. Ajouter colonne dossier_id à cm_batch si elle n'existe pas déjà
ALTER TABLE cm_batch 
ADD COLUMN IF NOT EXISTS dossier_id BIGINT NOT NULL DEFAULT @default_dossier_id;

-- 4. Mettre à jour les enregistrements existants avec le dossier par défaut
UPDATE cm_batch 
SET dossier_id = @default_dossier_id 
WHERE dossier_id IS NULL OR dossier_id = 0;

-- 5. Ajouter un index pour améliorer les performances des requêtes par dossier
CREATE INDEX IF NOT EXISTS idx_cm_batch_dossier_id ON cm_batch(dossier_id);

-- 6. Ajouter une contrainte de clé étrangère vers dossiers
ALTER TABLE cm_batch 
ADD CONSTRAINT fk_cm_batch_dossier 
FOREIGN KEY (dossier_id) REFERENCES dossiers(id) ON DELETE RESTRICT;

-- ============================================================================
-- VÉRIFICATION
-- ============================================================================

-- Afficher le nombre d'enregistrements par dossier
SELECT 
    d.name AS dossier_name,
    COUNT(cb.id) AS batch_count
FROM cm_batch cb
JOIN dossiers d ON cb.dossier_id = d.id
GROUP BY d.id, d.name
ORDER BY batch_count DESC;

-- ============================================================================
-- FIN DE LA MIGRATION
-- ============================================================================
