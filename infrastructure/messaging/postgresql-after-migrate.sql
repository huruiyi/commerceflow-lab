GRANT SELECT, INSERT ON messaging_platform.messaging_deliveries TO messaging_runtime;
GRANT UPDATE (status, finished_at) ON messaging_platform.messaging_deliveries TO messaging_runtime;
