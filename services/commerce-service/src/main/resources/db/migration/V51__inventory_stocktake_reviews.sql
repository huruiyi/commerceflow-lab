CREATE TABLE inventory_stocktake_reviews (
  reference VARCHAR(80) PRIMARY KEY,
  proposal JSON NOT NULL,
  counted_by VARCHAR(100) NOT NULL,
  approved_by VARCHAR(100),
  approved_at TIMESTAMP(6),
  completed_at TIMESTAMP(6),
  receipt JSON,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
