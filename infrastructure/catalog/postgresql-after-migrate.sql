GRANT SELECT ON catalog.products, catalog.product_translations, catalog.catalog_product_skus,
  catalog.catalog_event_outbox, catalog.catalog_search_documents, catalog.catalog_search_state,
  catalog.catalog_elasticsearch_state, catalog.catalog_monitor_attribute_values TO catalog_runtime;

GRANT INSERT (merchant_id,create_request_id,create_fingerprint,name,brief,description,price,image_url,thumbnail_url,
      lifecycle_status,business_version,listing_product_id),
      UPDATE (name,brief,description,image_url,thumbnail_url,price,lifecycle_status,monitor_profile,business_version)
  ON catalog.products TO catalog_runtime;
GRANT INSERT (product_id,locale,name,brief,description), UPDATE (name,brief,description)
  ON catalog.product_translations TO catalog_runtime;
GRANT INSERT (product_id,merchant_id,code,spec_key,specifications,default_sku,stock_product_id,unit_price,
      lifecycle_status,request_id,create_fingerprint),
      UPDATE (code,unit_price,lifecycle_status,spec_key,specifications,business_version,stock_product_id)
  ON catalog.catalog_product_skus TO catalog_runtime;
GRANT INSERT (event_id,event_type,aggregate_id,schema_version,business_version,occurred_at,correlation_id,causation_id,payload),
      UPDATE (status,attempts,published_at,last_error,next_attempt_at,search_indexed_at,elasticsearch_indexed_at)
  ON catalog.catalog_event_outbox TO catalog_runtime;
GRANT INSERT (generation,product_id,locale,merchant_id,business_version,name,brief,description,searchable,unit_price),
      DELETE ON catalog.catalog_search_documents TO catalog_runtime;
GRANT UPDATE (generation,rebuild_requested,rebuilt_at) ON catalog.catalog_search_state TO catalog_runtime;
GRANT UPDATE (generation,catalog_generation) ON catalog.catalog_elasticsearch_state TO catalog_runtime;
