-- Keep historical rows available to database operators and remove dependencies on active tables.
ALTER TABLE customer_orders
  DROP FOREIGN KEY fk_customer_orders_user,
  RENAME TO retired_customer_orders;
