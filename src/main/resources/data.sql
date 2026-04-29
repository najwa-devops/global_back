-- ============================================
-- SUPPRESSION ET RECRÉATION DES PATTERNS
-- ============================================
DELETE FROM field_patterns;

-- ============================================
-- NUMÉRO DE FACTURE - PATTERNS AMÉLIORÉS
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'invoiceNumber';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 0 : "Numéro de facture : XXX"
('invoiceNumber', '(?i)num[ée]ro\\s+de\\s+facture\\s*[:.]?\\s*([\\dA-Z][\\dA-Z\\s/-]{3,25})', 0, true, 'Numéro de facture : XXX'),

-- Pattern 1 : Format date-code (202501-FA002)
('invoiceNumber', '(?i)facture\s+n?[°o#]?\s*[:.]?\s*(\d{6}-[A-Z]{2}\d{3})', 1, true, 'Format: 202501-FA002'),

-- Pattern 2 : Format avec tirets et lettres (328974-25-DHJ)
('invoiceNumber', '(?i)facture\s+n?[°o#]?\s*[:.]?\s*(\d{5,7}-\d{2}-[A-Z]{2,5})', 2, true, 'Format: 328974-25-DHJ'),

-- Pattern 3 : Format avec espaces et slashes (0125 11 / 2025)
('invoiceNumber', '(?i)facture\s+n?[°o#]?\s*[:.]?\s*(\d{4}\s+\d{2}\s*/?\s*\d{4})', 3, true, 'Format: 0125 11 / 2025'),

-- Pattern 4 : Format alphanumérique mixte (très flexible)
('invoiceNumber', '(?i)facture\s+n?[°o#]?\s*[:.]?\s*([\dA-Z][\dA-Z\s/-]{3,20})', 4, true, 'Format mixte flexible'),

-- Pattern 5 : Fallback - capture tout après "Facture"
('invoiceNumber', '(?i)facture\s+n?[°o#]?\s*[:.]?\s*([^\n\r]{3,25}?)(?=\s*(?:date|le|$))', 5, true, 'Fallback générique');
-- ============================================
-- DATE DE FACTURE
-- ============================================
INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
                                                                                          ('invoiceDate', '(?i)date\\s+de\\s+facture\\s*[:.]?\\s*(\\d{2}[-/]\\d{2}[-/]\\d{4})', 0, true, 'Date de facture:'),
                                                                                          ('invoiceDate', '(?i)date\s+facturation\s*[:.]?\s*(\d{2}[/-]\d{2}[/-]\d{4})', 1, true, 'Date facturation:'),
                                                                                          ('invoiceDate', '(?i)date\s+de\s+la\s+facture\s*[:.]?\s*(\d{2}[-/]\d{2}[-/]\d{4})', 2, true, 'Date de la facture:'),
                                                                                          ('invoiceDate', '(\d{2}[-/]\d{2}[-/]\d{4})', 3, true, 'Format JJ/MM/AAAA');


-- ============================================
-- FOURNISSEUR - PATTERNS AMÉLIORÉS
-- ============================================
INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES

--PRIORITÉ 1 : "Émetteur" + nom (le plus fiable)
('supplier', '(?i)(?:émetteur|emetteur)\s+([A-Z][A-Za-z\s&.,''-]{3,60})', 1, true, 'Émetteur + nom'),

--PRIORITÉ 2 : Nom + SARL/SAS/SA (très fiable)
('supplier', '([A-Z][A-Za-z\s&.,''()-]{3,50}\s+(?:SARL|SAS|SA|S\.A\.R\.L\.?|S\.A\.S\.?|S\.A\.?))', 2, true, 'Nom + statut juridique'),

--PRIORITÉ 3 : Ligne isolée en MAJUSCULES (début du document, lignes 1-15)
-- Note: Ce pattern sera appliqué manuellement en Java pour contrôler la zone de recherche
('supplier', '(?m)^([A-Z][A-Z\s&.,''()-]{5,60})$', 3, true, 'Ligne MAJUSCULES isolée'),

--PRIORITÉ 4 : Ligne isolée Titre Case (début du document)
('supplier', '(?m)^([A-Z][A-Za-z\s&.,''()-]{5,60})$', 4, true, 'Ligne Titre Case isolée');

-- ============================================
-- ICE - PATTERNS ULTRA-FLEXIBLES
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'ice';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 1 : ICE avec espaces après ":"
('ice', '(?i)ICE\s*[:\s]+\s*(\d{3}\s*\d{3}\s*\d{3}\s*\d{3}\s*\d{3})', 1, true, 'ICE: 003 501 940 000 019'),

-- Pattern 2 : ICE sans espaces
('ice', '(?i)ICE\s*[:\s]+\s*(\d{15})', 2, true, 'ICE: 003501940000019'),

-- Pattern 3 : I.C.E. avec points
('ice', '(?i)I\.\s*C\.\s*E\.?\s*[:\s]+\s*(\d{15})', 3, true, 'I.C.E.: 003501940000019'),

-- Pattern 4 : Fallback - 15 chiffres après ICE
('ice', '(?i)ICE[^\d]{0,10}(\d{15})', 4, true, 'ICE suivi de 15 chiffres');


-- ============================================
-- IF - PATTERNS AMÉLIORÉS (FOOTER UNIQUEMENT)
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'ifNumber';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 1 : I.F. avec espaces flexibles
('ifNumber', '(?i)I\.\s*F\.\s*[:\s]+\s*(\d{7,10})', 1, true, 'I.F.: 1234567'),

-- Pattern 0 : IF N°: 1234567
('ifNumber', '(?i)IF\\s*N\\s*[°o0]?\\s*[:\\s]+\\s*(\\d{7,10})', 0, true, 'IF N°: 1234567'),

-- Pattern 2 : IF sans points
('ifNumber', '(?i)IF\s*[:\s]+\s*(\d{7,10})', 2, true, 'IF: 1234567'),

-- Pattern 3 : Identifiant Fiscal
('ifNumber', '(?i)Identifiant\s*Fiscal\s*[:\s]+\s*(\d{7,10})', 3, true, 'Identifiant Fiscal: 1234567'),

('ifNumber', '(?i)F\.\s*[:\s]+\s*(\d{7,10})', 3, true, 'F.: 1234567'),

('ifNumber', '(?i)\.\s*F\.\s*[:\s]+\s*(\d{7,10})', 3, true, '.F.: 1234567');

-- ============================================
-- MONTANT HT - PATTERNS ULTRA-FLEXIBLES
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'amountHT';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 1 : "Total HT" avec espaces variables
('amountHT', '(?i)total\s+h\.?t\.?\s*[:.]?\s*([\d\s,.]{3,15})', 1, true, 'Total HT 7 000,00'),

-- Pattern 2 : "Montant total H.T"
('amountHT', '(?i)montant\s+total\s+h\.?t\.?\s*[:.]?\s*([\d\s,.]{3,15})', 2, true, 'Montant total H.T'),

-- Pattern 3 : "Prix (H.T)" ou "Prix H.T:"
('amountHT', '(?i)prix\s*\(?h\.?t\.?\)?s*[:.]?\s*([\d\s,.]{3,15})', 3, true, 'Prix (H.T) : 600,00'),

-- Pattern 4 : Ligne avec "H.T" et montant
('amountHT', '(?i)h\.?t\.?\s*[:.]?\s*([\d\s,.]{3,15})\s*(?:DH|MAD|€)?', 4, true, 'H.T : 448.00 DH'),

('amountHT','(?i)prix\s*\(\s*h\s*l\s*t\s*\)\s*[:.]?\s*([\d\s]+[,.]\d{2})',1, true,'Prix (HLT) : 600,00'),
-- Pattern 5 : Fallback - "Hors taxe"
('amountHT', '(?i)hors\s+taxe?s?\s*[:.]?\s*([\d\s,.]{3,15})', 5, true, 'Hors taxe : XXX');


-- ============================================
-- TVA - PATTERNS ULTRA-FLEXIBLES
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'tva';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 1 : "Total TVA 20%"
('tva', '(?i)total\s+t\.?v\.?a\.?\s+\d*%?\s*[:.]?\s*([\d\s,.]{2,12})', 1, true, 'Total TVA 20% 1 400,00'),

-- Pattern 2 : "TVA 20%"
('tva', '(?i)t\.?v\.?a\.?\s+\d+%\s*[:.]?\s*([\d\s,.]{2,12})', 2, true, 'TVA 20% : 89.60'),

-- Pattern 3 : "T.V.A. 20% :"
('tva', '(?i)t\.?\s*v\.?\s*a\.?\s*\d*%?\s*[:.]?\s*([\d\s,.]{2,12})', 3, true, 'T.V.A. 20% : 120,00'),

('tva','(?i)t\.?\s*v\.?\s*a\.?\s*,?\s*\d{1,2}%\s*[:.]?\s*([\d\s]+[,.]\d{2})',1, true,'T.V.A, 20% : 120,00'),

('tva', 'Tv?\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%', 1, true, 'Tv 20 %'),
-- Pattern 4 : Fallback - ligne avec "TVA" et montant
('tva', '(?i)t\.?v\.?a\.?\s*[:.]?\s*([\d\s,.]{2,12})\s*(?:DH|MAD|€)?', 4, true, 'TVA : 89.60 DH');


-- ============================================
-- MONTANT TTC - PATTERNS ULTRA-FLEXIBLES
-- ============================================
DELETE FROM field_patterns WHERE field_name = 'amountTTC';

INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES
-- Pattern 1 : "Total TTC _"
('amountTTC', '(?i)total\s+t\.?t\.?c\.?\s*[_:]?\s*([\d\s,.]{3,15})', 1, true, 'Total TTC _ 8 400,00'),

-- Pattern 2 : "Montant total T.T.C"
('amountTTC', '(?i)montant\s+total\s+t\.?t\.?c\.?\s*[:.]?\s*([\d\s,.]{3,15})', 2, true, 'Montant total T.T.C'),

-- Pattern 3 : "Prix (T.T.C.)"
('amountTTC', '(?i)prix\s*\(?t\.?t\.?c\.?\)?s*[:.]?\s*([\d\s,.]{3,15})', 3, true, 'Prix (T.T.C.) : 720,00'),

-- Pattern 4 : Ligne avec "T.T.C" et montant
('amountTTC', '(?i)t\.?t\.?c\.?\s*[:.]?\s*([\d\s,.]{3,15})\s*(?:DH|MAD|€)?', 4, true, 'T.T.C : 537.60 DH'),

-- Pattern 5 : Fallback - "Total :" (dernier recours)
('amountTTC', '(?i)total\s*[:.]?\s*([\d\s,.]{3,15})\s*(?:DH|MAD|€)', 5, true, 'Total : 720,00 DH');


INSERT INTO field_patterns (field_name, pattern_regex, priority, active, description) VALUES

-- Pattern 1 : "R.C : 12345"
('rcNumber', '(?i)r\.?\s*c\.?\s*[:.]?\s*(\d{1,10})', 1, true, 'R.C : 12345'),

-- Pattern 2 : "Registre Commerce : 12345"
('rcNumber', '(?i)registre\s+(?:de\s+)?commerce\s*[:.]?\s*(\d{1,10})', 2, true, 'Registre Commerce: 12345'),

-- Pattern 3 : "RC N° 12345"
('rcNumber', '(?i)rc\s+n[°o]?\s*[:.]?\s*(\d{1,10})', 3, true, 'RC N° 12345'),

-- Pattern 4 : Fallback - dans footer après "R.C"
('rcNumber', '(?i)r\.?\s*c\.?\s+(\d{5,10})', 4, true, 'R.C 12345');

