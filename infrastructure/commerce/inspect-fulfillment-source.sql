SELECT CONCAT(
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema=DATABASE()
     AND table_name IN ('fulfillment_order_locks','fulfillment_command_ids','fulfillment_shipment_jobs',
       'fulfillment_merchant_jobs','fulfillment_shipment_recoveries','fulfillment_merchant_recoveries',
       'fulfillment_shadow_clock','fulfillment_shadow_changes')),
  '/',
  (SELECT COUNT(*) FROM information_schema.triggers
   WHERE trigger_schema=DATABASE() AND trigger_name REGEXP '^trg_fulfillment_shadow_[1-6]_(insert|update|delete)$'),
  '/',
  (SELECT COUNT(*) FROM information_schema.routines
   WHERE routine_schema=DATABASE() AND routine_name='record_fulfillment_shadow_change' AND routine_type='PROCEDURE')
);
