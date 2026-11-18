CREATE TABLE fulfillment_write_admission (
  singleton_id TINYINT NOT NULL PRIMARY KEY,
  frozen BOOLEAN NOT NULL,
  CHECK (singleton_id = 1)
);

INSERT INTO fulfillment_write_admission (singleton_id, frozen) VALUES (1, FALSE);
