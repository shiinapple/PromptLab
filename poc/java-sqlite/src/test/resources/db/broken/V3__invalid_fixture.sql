CREATE TABLE should_rollback (id INTEGER PRIMARY KEY);
INSERT INTO missing_fixture_table (id) VALUES (1);
