ALTER TABLE reference_nodes ADD COLUMN source_metadata JSONB
  CHECK (jsonb_typeof(source_metadata) = 'object');

COMMENT ON COLUMN reference_nodes.source_metadata IS 'Original attributes and codes from the source snapshot.';
