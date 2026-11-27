CREATE TABLE user_settings (
    user_id TEXT NOT NULL PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    locale TEXT NOT NULL CHECK (locale IN ('zh-CN', 'en')),
    editor_prefs_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE drafts (
    user_id TEXT NOT NULL REFERENCES users ON DELETE CASCADE,
    problem_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    case_id TEXT NOT NULL,
    slots_json TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, problem_id, version),
    FOREIGN KEY (problem_id, version, case_id) REFERENCES problem_cases
);
