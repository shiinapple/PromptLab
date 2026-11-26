CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    label TEXT NOT NULL UNIQUE
);

CREATE TABLE runs (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'DONE')),
    result TEXT
);

CREATE INDEX runs_user_idx ON runs(user_id);
