CREATE TABLE users (
    id TEXT NOT NULL PRIMARY KEY,
    created_at INTEGER NOT NULL
);
CREATE TABLE problem_versions (
    problem_id TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    content_hash TEXT NOT NULL,
    published_at INTEGER NOT NULL,
    PRIMARY KEY (problem_id, version)
);
CREATE TABLE problem_cases (
    problem_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    case_id TEXT NOT NULL,
    PRIMARY KEY (problem_id, version, case_id),
    FOREIGN KEY (problem_id, version) REFERENCES problem_versions
);
CREATE TABLE prompt_snapshots (
    id TEXT NOT NULL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users ON DELETE CASCADE,
    problem_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    case_id TEXT NOT NULL,
    slots_json TEXT NOT NULL,
    messages_json TEXT NOT NULL,
    request_params_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, id),
    FOREIGN KEY (problem_id, version, case_id) REFERENCES problem_cases
);
CREATE TABLE runs (
    id TEXT NOT NULL PRIMARY KEY,
    user_id TEXT NOT NULL,
    snapshot_id TEXT NOT NULL UNIQUE,
    mode TEXT NOT NULL CHECK (mode IN ('DIRECT', 'D0')),
    idempotency_key TEXT NOT NULL,
    create_digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELED', 'EXPIRED')),
    result_digest TEXT,
    raw_output TEXT,
    reported_meta_json TEXT,
    failure_code TEXT,
    verdict TEXT CHECK (verdict IN ('PASSED', 'FORMAT_ERROR', 'WRONG_ANSWER')),
    checks_json TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    finished_at INTEGER,
    UNIQUE (user_id, id),
    UNIQUE (user_id, idempotency_key),
    FOREIGN KEY (user_id, snapshot_id) REFERENCES prompt_snapshots (user_id, id) ON DELETE CASCADE,
    CHECK ((status = 'PENDING' AND finished_at IS NULL)
        OR (status <> 'PENDING' AND finished_at IS NOT NULL)),
    CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELED') AND result_digest IS NOT NULL)
        OR (status IN ('PENDING', 'EXPIRED') AND result_digest IS NULL)),
    CHECK ((status = 'COMPLETED' AND verdict IS NOT NULL AND raw_output IS NOT NULL AND checks_json IS NOT NULL)
        OR (status <> 'COMPLETED' AND verdict IS NULL AND raw_output IS NULL AND checks_json IS NULL)),
    CHECK ((status = 'FAILED' AND failure_code IS NOT NULL)
        OR (status <> 'FAILED' AND failure_code IS NULL))
);
CREATE INDEX snapshots_by_user_problem ON prompt_snapshots (user_id, problem_id, id);
CREATE INDEX runs_by_user_created ON runs (user_id, created_at DESC, id DESC);
CREATE INDEX runs_pending_expiry ON runs (expires_at) WHERE status = 'PENDING';
