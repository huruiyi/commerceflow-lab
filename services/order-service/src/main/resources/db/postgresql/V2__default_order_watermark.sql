INSERT INTO order_shadow_watermark (replica_name)
VALUES ('commerce-order-v1')
ON CONFLICT (replica_name) DO NOTHING;
