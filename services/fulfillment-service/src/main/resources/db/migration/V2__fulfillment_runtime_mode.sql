CREATE TABLE fulfillment_runtime_mode (
  singleton_id TINYINT PRIMARY KEY,
  mode VARCHAR(16) NULL,
  CHECK (singleton_id = 1)
);

INSERT INTO fulfillment_runtime_mode (singleton_id,mode)
SELECT singleton_id,CASE WHEN generation > 0 THEN 'SHADOW' END FROM fulfillment_shadow_watermark;
