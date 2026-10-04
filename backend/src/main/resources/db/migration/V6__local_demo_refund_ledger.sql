CREATE TABLE refund_ledger (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    action_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    refunded_total_cents BIGINT NOT NULL CHECK (refunded_total_cents > 0),
    execution_mode VARCHAR(32) NOT NULL CHECK (execution_mode = 'LOCAL_DEMO_LEDGER'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    FOREIGN KEY (tenant_id, action_id) REFERENCES sensitive_actions(tenant_id, id),
    UNIQUE (tenant_id, action_id)
);

CREATE INDEX refund_ledger_tenant_order_idx ON refund_ledger(tenant_id, order_id, created_at DESC);
