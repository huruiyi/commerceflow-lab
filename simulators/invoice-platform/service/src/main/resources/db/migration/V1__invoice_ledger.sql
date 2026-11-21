CREATE TABLE invoice_requests (
    request_id VARCHAR(64) NOT NULL,
    kind ENUM('ISSUE', 'RED') NOT NULL,
    reference_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64) NULL,
    original_invoice_no VARCHAR(64) NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    simulation ENUM('SUCCESS', 'FAIL', 'ACCEPT_THEN_TIMEOUT') NOT NULL,
    status ENUM('RECEIVED', 'ACCEPTED', 'SUCCEEDED', 'FAILED') NOT NULL,
    invoice_no VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    accepted_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    process_after DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    active_reference VARCHAR(129) GENERATED ALWAYS AS (
        CASE WHEN status IN ('ACCEPTED', 'SUCCEEDED') THEN
            CASE WHEN kind = 'ISSUE' THEN reference_no ELSE CONCAT(original_invoice_no, ':', reference_no) END
        END
    ) STORED,
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_invoice_active_reference (kind, active_reference),
    KEY idx_invoice_pending (status, process_after, request_id),
    KEY idx_invoice_red_balance (original_invoice_no, status, amount),
    CONSTRAINT chk_invoice_request_amount CHECK (amount > 0),
    CONSTRAINT chk_invoice_request_origin CHECK (
        (kind = 'ISSUE' AND payment_no IS NOT NULL AND original_invoice_no IS NULL) OR
        (kind = 'RED' AND payment_no IS NULL AND original_invoice_no IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin;

CREATE TABLE invoice_tickets (
    invoice_no VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    kind ENUM('ISSUE', 'RED') NOT NULL,
    original_invoice_no VARCHAR(64) NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (invoice_no),
    UNIQUE KEY uk_invoice_ticket_request (request_id),
    KEY idx_invoice_ticket_original (original_invoice_no),
    CONSTRAINT chk_invoice_ticket_amount CHECK (amount > 0),
    CONSTRAINT fk_invoice_ticket_request FOREIGN KEY (request_id) REFERENCES invoice_requests (request_id),
    CONSTRAINT fk_invoice_ticket_original FOREIGN KEY (original_invoice_no) REFERENCES invoice_tickets (invoice_no)
) ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin;

CREATE TABLE invoice_receipts (
    event_id VARCHAR(68) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    delivered_at DATETIME(6) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_invoice_receipt_request (request_id),
    KEY idx_invoice_receipt_due (delivered_at, next_attempt_at, event_id),
    CONSTRAINT fk_invoice_receipt_request FOREIGN KEY (request_id) REFERENCES invoice_requests (request_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin;
