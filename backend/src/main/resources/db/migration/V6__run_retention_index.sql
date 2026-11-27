CREATE INDEX runs_finished_retention ON runs (finished_at, id) WHERE finished_at IS NOT NULL;
