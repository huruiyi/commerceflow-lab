INSERT INTO catalog_search_state(singleton_id,generation,rebuild_requested)
VALUES (1,0,TRUE)
ON CONFLICT (singleton_id) DO NOTHING;

INSERT INTO catalog_elasticsearch_state(singleton_id,generation,catalog_generation)
VALUES (1,0,0)
ON CONFLICT (singleton_id) DO NOTHING;

CREATE FUNCTION catalog_touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW IS DISTINCT FROM OLD AND NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
    NEW.updated_at := statement_timestamp();
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER products_touch_updated_at
BEFORE UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION catalog_touch_updated_at();

CREATE TRIGGER catalog_product_skus_touch_updated_at
BEFORE UPDATE ON catalog_product_skus
FOR EACH ROW EXECUTE FUNCTION catalog_touch_updated_at();
