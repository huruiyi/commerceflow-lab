-- Run against the stopped execution database after draining and completing the reverse copy, if needed.
-- Supply @expected_generation and @expected_source_sequence from the verified handoff record.
START TRANSACTION;

SELECT mode FROM fulfillment_runtime_mode WHERE singleton_id = 1 FOR UPDATE;

UPDATE fulfillment_runtime_mode AS runtime
JOIN fulfillment_shadow_watermark AS watermark ON watermark.singleton_id = runtime.singleton_id
SET runtime.mode = 'RETIRED'
WHERE runtime.singleton_id = 1
  AND runtime.mode = 'EXECUTION'
  AND watermark.generation > 0
  AND watermark.generation = @expected_generation
  AND watermark.source_sequence = @expected_source_sequence
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_shipment_jobs
    WHERE status IN ('PENDING', 'REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_merchant_jobs
    WHERE status IN ('PENDING', 'REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL
  );

SET @retired = ROW_COUNT();
COMMIT;

SELECT @retired AS retired;
