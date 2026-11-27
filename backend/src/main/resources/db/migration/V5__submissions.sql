CREATE TABLE submissions (
    id TEXT NOT NULL PRIMARY KEY,
    user_id TEXT NOT NULL,
    run_id TEXT NOT NULL UNIQUE,
    submitted_at INTEGER NOT NULL,
    FOREIGN KEY (user_id, run_id) REFERENCES runs (user_id, id) ON DELETE CASCADE
);
CREATE INDEX submissions_by_user_time ON submissions (user_id, submitted_at DESC, id DESC);
