CREATE INDEX ix_product_reviews_product_page
  ON product_reviews (product_id, created_at DESC, id DESC, rating);
