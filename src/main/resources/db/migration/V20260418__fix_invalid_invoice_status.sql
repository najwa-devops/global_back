-- Fix rows where status is empty string or an unknown value
-- Maps them to PENDING so Hibernate can load them without crashing
UPDATE dynamic_invoice
SET status = 'PENDING'
WHERE status IS NULL
   OR status = ''
   OR status NOT IN (
       'PENDING', 'PROCESSING', 'TREATED', 'RECALCULATED',
       'OUT_OF_PERIOD', 'DUPLICATE', 'ACCOUNTED',
       'READY_TO_VALIDATE', 'VALIDATED', 'ERROR'
   );
