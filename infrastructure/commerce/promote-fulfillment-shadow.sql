-- Run against the stopped target after the source is frozen, drained, verified and fenced.
-- The operator supplies @expected_generation and @expected_source_sequence from the final report.
START TRANSACTION;

SELECT mode FROM fulfillment_runtime_mode WHERE singleton_id = 1 FOR UPDATE;

UPDATE fulfillment_runtime_mode AS runtime
JOIN fulfillment_shadow_watermark AS watermark ON watermark.singleton_id = runtime.singleton_id
SET runtime.mode = 'EXECUTION'
WHERE runtime.singleton_id = 1
  AND runtime.mode = 'SHADOW'
  AND watermark.generation > 0
  AND watermark.generation = @expected_generation
  AND watermark.source_sequence = @expected_source_sequence
  AND NOT EXISTS (SELECT 1 FROM fulfillment_shadow_quarantine)
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_shipment_jobs WHERE status IN ('PENDING', 'REVIEW_REQUIRED')
  )
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_merchant_jobs WHERE status IN ('PENDING', 'REVIEW_REQUIRED')
  );

SET @promoted = ROW_COUNT();
COMMIT;

SELECT @promoted AS promoted;
