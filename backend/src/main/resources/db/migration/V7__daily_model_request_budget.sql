CREATE TABLE model_request_budget_daily (
    budget_date DATE PRIMARY KEY,
    attempts SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 50),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

GRANT SELECT, INSERT, UPDATE ON model_request_budget_daily TO aftercare_app;
