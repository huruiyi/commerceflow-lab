CREATE TABLE invoice_requests (
    request_id VARCHAR(64) COLLATE "C" NOT NULL,
    kind VARCHAR(5) COLLATE "C" NOT NULL CHECK (kind IN ('ISSUE', 'RED')),
    reference_no VARCHAR(64) COLLATE "C" NOT NULL,
    payment_no VARCHAR(64) COLLATE "C" NULL,
    original_invoice_no VARCHAR(64) COLLATE "C" NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) COLLATE "C" NOT NULL,
    simulation VARCHAR(19) COLLATE "C" NOT NULL CHECK (simulation IN ('SUCCESS', 'FAIL', 'ACCEPT_THEN_TIMEOUT')),
    status VARCHAR(9) COLLATE "C" NOT NULL CHECK (status IN ('RECEIVED', 'ACCEPTED', 'SUCCEEDED', 'FAILED')),
    invoice_no VARCHAR(64) COLLATE "C" NULL,
    failure_code VARCHAR(64) COLLATE "C" NULL,
    accepted_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'UTC'),
    process_after TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    active_reference VARCHAR(129) COLLATE "C" GENERATED ALWAYS AS (
        CASE WHEN status IN ('ACCEPTED', 'SUCCEEDED') THEN
            CASE WHEN kind = 'ISSUE' THEN reference_no ELSE original_invoice_no || ':' || reference_no END
        END
    ) STORED,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_invoice_active_reference UNIQUE (kind, active_reference),
    CONSTRAINT chk_invoice_request_amount CHECK (amount > 0),
    CONSTRAINT chk_invoice_request_origin CHECK (
        (kind = 'ISSUE' AND payment_no IS NOT NULL AND original_invoice_no IS NULL) OR
        (kind = 'RED' AND payment_no IS NULL AND original_invoice_no IS NOT NULL)
    )
);

CREATE TABLE invoice_tickets (
    invoice_no VARCHAR(64) COLLATE "C" NOT NULL,
    request_id VARCHAR(64) COLLATE "C" NOT NULL,
    kind VARCHAR(5) COLLATE "C" NOT NULL CHECK (kind IN ('ISSUE', 'RED')),
    original_invoice_no VARCHAR(64) COLLATE "C" NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency CHAR(3) COLLATE "C" NOT NULL,
    issued_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'UTC'),
    PRIMARY KEY (invoice_no),
    CONSTRAINT uk_invoice_ticket_request UNIQUE (request_id),
    CONSTRAINT chk_invoice_ticket_amount CHECK (amount > 0),
    CONSTRAINT fk_invoice_ticket_request FOREIGN KEY (request_id) REFERENCES invoice_requests (request_id),
    CONSTRAINT fk_invoice_ticket_original FOREIGN KEY (original_invoice_no) REFERENCES invoice_tickets (invoice_no)
);

CREATE TABLE invoice_receipts (
    event_id VARCHAR(68) COLLATE "C" NOT NULL,
    request_id VARCHAR(64) COLLATE "C" NOT NULL,
    next_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL DEFAULT (statement_timestamp() AT TIME ZONE 'UTC'),
    attempts BIGINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4294967295),
    delivered_at TIMESTAMP(6) WITHOUT TIME ZONE NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_invoice_receipt_request UNIQUE (request_id),
    CONSTRAINT fk_invoice_receipt_request FOREIGN KEY (request_id) REFERENCES invoice_requests (request_id)
);

CREATE INDEX idx_invoice_pending ON invoice_requests (status, process_after, request_id);
CREATE INDEX idx_invoice_red_balance ON invoice_requests (original_invoice_no, status, amount);
CREATE INDEX idx_invoice_ticket_original ON invoice_tickets (original_invoice_no);
CREATE INDEX idx_invoice_receipt_due ON invoice_receipts (delivered_at, next_attempt_at, event_id);
CREATE INDEX idx_invoice_monitor_accepted ON invoice_requests (kind, status, accepted_at);
