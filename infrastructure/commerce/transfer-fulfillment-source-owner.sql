-- Run against commerceflow with both execution processes stopped.
-- Set @expected_owner, @next_owner and @expected_source_sequence in this session.
SET @owner_lock = GET_LOCK('commerceflow.fulfillment-source-owner', 30);
START TRANSACTION;

UPDATE fulfillment_write_admission AS admission
JOIN fulfillment_shadow_clock AS clock ON clock.singleton_id = admission.singleton_id
SET admission.write_owner = @next_owner
WHERE @owner_lock = 1
  AND admission.singleton_id = 1
  AND admission.frozen = TRUE
  AND admission.write_owner = @expected_owner
  AND @next_owner IN ('COMMERCE', 'FULFILLMENT')
  AND @expected_owner <> @next_owner
  AND clock.last_sequence = @expected_source_sequence
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_shipment_jobs
    WHERE status IN ('PENDING', 'REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM fulfillment_merchant_jobs
    WHERE status IN ('PENDING', 'REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL
  );

SET @transferred = ROW_COUNT();
COMMIT;
DO RELEASE_LOCK('commerceflow.fulfillment-source-owner');

SELECT @transferred AS transferred;
