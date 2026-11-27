\set ON_ERROR_STOP on
\getenv runtime_password COMMERCE_POSTGRES_RUNTIME_PASSWORD
BEGIN;
SELECT 'CREATE ROLE commerce_runtime LOGIN' WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'commerce_runtime'
) \gexec
ALTER ROLE commerce_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
GRANT USAGE ON SCHEMA commerceflow TO commerce_runtime;
GRANT DELETE, INSERT, SELECT, UPDATE ON TABLE
    commerceflow.app_users,
    commerceflow.cart_items,
    commerceflow.carts,
    commerceflow.checkout_intents,
    commerceflow.coupon_campaigns,
    commerceflow.customer_coupons,
    commerceflow.inventory_command_delivery_outbox,
    commerceflow.inventory_command_result_inbox,
    commerceflow.inventory_stocktake_reviews,
    commerceflow.invoice_applications,
    commerceflow.invoice_attempts,
    commerceflow.invoice_frozen_refunds,
    commerceflow.invoice_refund_facts,
    commerceflow.invoice_review_actions,
    commerceflow.line_after_sale_order_locks,
    commerceflow.line_after_sales,
    commerceflow.logistics_fulfillment_commands,
    commerceflow.merchant_payout_attempts,
    commerceflow.merchant_settlement_accounts,
    commerceflow.merchant_settlement_entries,
    commerceflow.merchant_settlement_statements,
    commerceflow.merchant_shipping_rates,
    commerceflow.merchant_stores,
    commerceflow.notification_delivery_outbox,
    commerceflow.notification_delivery_recoveries,
    commerceflow.order_effect_inbox,
    commerceflow.order_effect_outbox,
    commerceflow.persistent_logins,
    commerceflow.product_reviews,
    commerceflow.return_exception_actions,
    commerceflow.return_exception_work_orders,
    commerceflow.roles,
    commerceflow.scheduled_task_events,
    commerceflow.scheduled_task_policies,
    commerceflow.scheduled_task_policy_events,
    commerceflow.scheduled_tasks,
    commerceflow.shedlock,
    commerceflow.shipment_events,
    commerceflow.shipment_notifications,
    commerceflow.shipment_records,
    commerceflow.shipping_addresses,
    commerceflow.user_roles
TO commerce_runtime;

GRANT INSERT, SELECT ON TABLE
    commerceflow.checkout_receipts,
    commerceflow.merchant_statement_difference_reviews
TO commerce_runtime;

GRANT INSERT, SELECT, UPDATE ON TABLE
    commerceflow.cart_repurchase_requests,
    commerceflow.merchant_statement_difference_dispositions,
    commerceflow.order_cancellation_completion
TO commerce_runtime;

GRANT SELECT ON TABLE
    commerceflow.fulfillment_write_admission,
    commerceflow.payment_report_merchant_grants
TO commerce_runtime;

SELECT format('GRANT USAGE ON SEQUENCE %I.%I TO commerce_runtime', sequence_schema.nspname, sequence.relname)
FROM pg_catalog.pg_class sequence
JOIN pg_catalog.pg_namespace sequence_schema ON sequence_schema.oid = sequence.relnamespace
JOIN pg_catalog.pg_depend ownership ON ownership.objid = sequence.oid
JOIN pg_catalog.pg_class owner_table ON owner_table.oid = ownership.refobjid
WHERE sequence.relkind = 'S' AND sequence_schema.nspname = 'commerceflow'
  AND ownership.classid = 'pg_class'::regclass AND ownership.refclassid = 'pg_class'::regclass
  AND ownership.deptype IN ('a','i') AND ownership.refobjsubid > 0
  AND has_table_privilege('commerce_runtime', owner_table.oid, 'INSERT')
\gexec

COMMIT;
