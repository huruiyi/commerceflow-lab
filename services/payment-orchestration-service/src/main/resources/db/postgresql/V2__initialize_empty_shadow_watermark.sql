INSERT INTO payment_shadow_watermark (replica_name) VALUES ('commerce-payment-v1')
ON CONFLICT (replica_name) DO NOTHING;
