CREATE TABLE account_deletion_requests (
    user_id TEXT NOT NULL PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    requested_at INTEGER NOT NULL,
    effective_at INTEGER NOT NULL CHECK (effective_at = requested_at + 259200000)
);
CREATE INDEX account_deletion_due ON account_deletion_requests (effective_at);
