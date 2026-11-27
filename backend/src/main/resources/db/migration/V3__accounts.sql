ALTER TABLE users ADD COLUMN email_normalized TEXT;
ALTER TABLE users ADD COLUMN password_hash TEXT;
ALTER TABLE users ADD COLUMN state TEXT NOT NULL DEFAULT 'DISABLED'
    CHECK (state IN ('ACTIVE', 'DISABLED'));
CREATE UNIQUE INDEX users_by_email ON users (email_normalized);
