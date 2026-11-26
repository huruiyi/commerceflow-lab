WITH permissions(table_name, readable, inserts, updates) AS (
  VALUES
    ('products',true,'merchant_id create_request_id create_fingerprint name brief description price image_url thumbnail_url lifecycle_status business_version listing_product_id',
     'name brief description image_url thumbnail_url price lifecycle_status monitor_profile business_version'),
    ('product_translations',true,'product_id locale name brief description',
     'name brief description'),
    ('catalog_product_skus',true,'product_id merchant_id code spec_key specifications default_sku stock_product_id unit_price lifecycle_status request_id create_fingerprint',
     'code unit_price lifecycle_status spec_key specifications business_version stock_product_id'),
    ('catalog_event_outbox',true,'event_id event_type aggregate_id schema_version business_version occurred_at correlation_id causation_id payload',
     'status attempts published_at last_error next_attempt_at search_indexed_at elasticsearch_indexed_at'),
    ('catalog_search_documents',true,'generation product_id locale merchant_id business_version name brief description searchable unit_price',
     ''),
    ('catalog_search_state',true,'',
     'generation rebuild_requested rebuilt_at'),
    ('catalog_elasticsearch_state',true,'',
     'generation catalog_generation'),
    ('catalog_cutover_state',false,'',
     ''),
    ('flyway_schema_history',false,'',
     '')
)
SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
  AND NOT pg_has_role(rolname,'catalog_migrator','MEMBER')
  AND NOT has_database_privilege(rolname,'catalog','CREATE,TEMP')
  AND NOT has_schema_privilege(rolname,'catalog','CREATE')
  AND NOT has_schema_privilege(rolname,'public','CREATE')
  AND has_schema_privilege(rolname,'catalog','USAGE')
  AND has_table_privilege(rolname,'catalog.catalog_monitor_attribute_values','SELECT')
  AND NOT has_table_privilege(rolname,'catalog.catalog_monitor_attribute_values','INSERT,UPDATE,DELETE')
  AND (SELECT schema_owner FROM information_schema.schemata WHERE schema_name='catalog')='catalog_migrator'
  AND (SELECT count(*) FROM information_schema.tables WHERE table_schema='catalog' AND table_type='BASE TABLE')=9
  AND NOT EXISTS (
    SELECT FROM permissions p LEFT JOIN information_schema.columns c
      ON c.table_schema='catalog' AND c.table_name=p.table_name
    WHERE c.column_name IS NULL
      OR has_table_privilege(rolname,format('catalog.%I',p.table_name),'TRUNCATE,TRIGGER')
      OR has_table_privilege(rolname,format('catalog.%I',p.table_name),'DELETE') <> (p.table_name='catalog_search_documents')
      OR has_column_privilege(rolname,format('catalog.%I',p.table_name),c.column_name,'SELECT') <> p.readable
      OR has_column_privilege(rolname,format('catalog.%I',p.table_name),c.column_name,'INSERT')
         <> (c.column_name=ANY(string_to_array(p.inserts,' ')))
      OR has_column_privilege(rolname,format('catalog.%I',p.table_name),c.column_name,'UPDATE')
         <> (c.column_name=ANY(string_to_array(p.updates,' ')))
  )
FROM pg_roles WHERE rolname='catalog_runtime';
