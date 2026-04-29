-- Add exercise period dates to dossiers table
-- Run once if the columns do not exist yet.

ALTER TABLE dossiers
    ADD COLUMN exercise_start_date DATE NULL,
    ADD COLUMN exercise_end_date DATE NULL;
