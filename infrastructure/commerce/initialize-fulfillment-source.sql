CREATE TEMPORARY TABLE bootstrap_guard (verified INT PRIMARY KEY);
INSERT INTO bootstrap_guard VALUES (0);
INSERT INTO bootstrap_guard VALUES (COALESCE(GET_LOCK('commerceflow.fulfillment-source-owner', 30), 0));

START TRANSACTION;
UPDATE fulfillment_write_admission AS admission
JOIN fulfillment_shadow_clock AS clock USING (singleton_id)
SET admission.write_owner = 'FULFILLMENT', admission.frozen = TRUE
WHERE admission.singleton_id = 1 AND admission.write_owner = 'COMMERCE' AND admission.frozen = FALSE
  AND clock.last_sequence = 0
  AND NOT EXISTS (SELECT 1 FROM fulfillment_shadow_changes)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_order_locks)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_command_ids)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_shipment_jobs)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_merchant_jobs)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_shipment_recoveries)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_merchant_recoveries)
  AND NOT EXISTS (SELECT 1 FROM shipment_records)
  AND NOT EXISTS (SELECT 1 FROM logistics_fulfillment_commands);

-- A duplicate sentinel aborts the batch, rolling back an incomplete initialization.
INSERT INTO bootstrap_guard
SELECT IF(EXISTS (
  SELECT 1 FROM fulfillment_write_admission
  WHERE singleton_id = 1 AND write_owner = 'FULFILLMENT' AND frozen = TRUE
), 2, 0);

COMMIT;
DO RELEASE_LOCK('commerceflow.fulfillment-source-owner');
