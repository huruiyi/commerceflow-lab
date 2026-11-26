INSERT INTO fulfillment_shadow_watermark (singleton_id,generation,source_sequence)
VALUES (1,0,0)
ON CONFLICT (singleton_id) DO NOTHING;

INSERT INTO fulfillment_runtime_mode (singleton_id,mode)
VALUES (1,NULL)
ON CONFLICT (singleton_id) DO NOTHING;
