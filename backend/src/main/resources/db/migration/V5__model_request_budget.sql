CREATE TABLE model_request_budget (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    attempts BIGINT NOT NULL CHECK (attempts >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO model_request_budget(singleton_id, attempts) VALUES (1, 0);

GRANT SELECT, UPDATE ON model_request_budget TO aftercare_app;
