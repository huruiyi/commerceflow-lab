CREATE TABLE merchant_statement_difference_dispositions (
  review_id CHAR(36) NOT NULL PRIMARY KEY COMMENT 'Original append-only difference review',
  receipt_request_id CHAR(36) NULL COMMENT 'Idempotency key for linking an owning business receipt',
  receipt VARCHAR(128) NULL COMMENT 'Authoritative inventory, payout or invoice operation reference',
  linked_by BIGINT NULL COMMENT 'Administrator who linked the business receipt',
  linked_at TIMESTAMP(6) NULL,
  closure_request_id CHAR(36) NULL COMMENT 'Idempotency key for closing the review',
  closed_by BIGINT NULL COMMENT 'Administrator who reran reconciliation and closed the review',
  closed_at TIMESTAMP(6) NULL,
  closure_reason VARCHAR(300) NULL COMMENT 'Rationale for closing after reconciliation',
  CONSTRAINT fk_statement_difference_disposition FOREIGN KEY (review_id)
    REFERENCES merchant_statement_difference_reviews(request_id)
);
