CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    owner_user_id UUID NOT NULL,
    title TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES users(tenant_id, id)
);

CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    conversation_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    request_id UUID NOT NULL,
    input_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_CONFIRMATION', 'COMPLETED', 'FAILED', 'CANCELLED')),
    deadline_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, request_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES users(tenant_id, id)
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    conversation_id UUID NOT NULL,
    run_id UUID,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL,
    structured_answer JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations(tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id)
);

CREATE TABLE run_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
    UNIQUE (tenant_id, run_id, sequence)
);

CREATE TABLE tool_invocations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    input_summary JSONB NOT NULL,
    output_summary JSONB,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    duration_ms BIGINT,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
    UNIQUE (tenant_id, run_id, invocation_id)
);

CREATE TABLE action_drafts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('AGENT', 'MANUAL')),
    run_id UUID,
    invocation_id VARCHAR(128),
    conversation_id UUID,
    created_by UUID NOT NULL,
    type VARCHAR(32) NOT NULL CHECK (type IN ('CREATE_TICKET', 'REQUEST_REFUND', 'REQUEST_STOCK_TRANSFER')),
    payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    evidence_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PREPARING', 'READY', 'CONFIRMED', 'INVALIDATED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id),
    CHECK ((origin = 'AGENT' AND run_id IS NOT NULL AND invocation_id IS NOT NULL AND conversation_id IS NOT NULL)
        OR (origin = 'MANUAL' AND run_id IS NULL AND invocation_id IS NULL))
);
CREATE UNIQUE INDEX action_drafts_agent_invocation_uq
    ON action_drafts(tenant_id, run_id, invocation_id) WHERE origin = 'AGENT';

CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    ticket_no VARCHAR(64) NOT NULL,
    draft_id UUID NOT NULL,
    source_run_id UUID,
    order_id UUID,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    priority VARCHAR(16) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_APPROVAL', 'RESOLVED', 'CLOSED')),
    evidence_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL,
    assigned_to UUID,
    resolution TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, ticket_no),
    UNIQUE (tenant_id, draft_id),
    FOREIGN KEY (tenant_id, draft_id) REFERENCES action_drafts(tenant_id, id),
    FOREIGN KEY (tenant_id, source_run_id) REFERENCES agent_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, assigned_to) REFERENCES users(tenant_id, id)
);
CREATE INDEX tickets_tenant_status_created_idx ON tickets(tenant_id, status, created_at DESC);

CREATE TABLE ticket_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    ticket_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets(tenant_id, id),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES users(tenant_id, id)
);

CREATE TABLE sensitive_actions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    draft_id UUID NOT NULL,
    ticket_id UUID,
    requested_by UUID NOT NULL,
    type VARCHAR(24) NOT NULL CHECK (type IN ('REFUND_REQUEST', 'STOCK_TRANSFER')),
    payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    evidence_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'EXECUTING', 'SUCCEEDED', 'SIMULATED_SUCCEEDED', 'FAILED')),
    expires_at TIMESTAMPTZ NOT NULL,
    execution_result JSONB,
    error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, draft_id),
    FOREIGN KEY (tenant_id, draft_id) REFERENCES action_drafts(tenant_id, id),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets(tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users(tenant_id, id)
);
CREATE INDEX sensitive_actions_tenant_status_expiry_idx ON sensitive_actions(tenant_id, status, expires_at);

CREATE TABLE approval_decisions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    action_id UUID NOT NULL,
    decided_by UUID NOT NULL,
    decision VARCHAR(8) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason VARCHAR(500) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, action_id) REFERENCES sensitive_actions(tenant_id, id),
    FOREIGN KEY (tenant_id, decided_by) REFERENCES users(tenant_id, id),
    UNIQUE (tenant_id, action_id)
);

CREATE TABLE command_dedup (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    actor_id UUID NOT NULL,
    command_name VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result JSONB,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES users(tenant_id, id),
    UNIQUE (tenant_id, actor_id, command_name, idempotency_key)
);

CREATE TABLE outbox_jobs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    aggregate_id UUID NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY', 'LEASED', 'DONE', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    lease_owner VARCHAR(128),
    lease_version BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, aggregate_id, job_type)
);
CREATE INDEX outbox_jobs_ready_idx ON outbox_jobs(status, available_at, lease_until);

CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    title TEXT NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    current_published_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE knowledge_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_id UUID NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    content_hash CHAR(64) NOT NULL,
    file_path TEXT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'PUBLISHED', 'FAILED', 'ARCHIVED')),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, document_id, version_no),
    FOREIGN KEY (tenant_id, document_id) REFERENCES knowledge_documents(tenant_id, id),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

ALTER TABLE knowledge_documents
    ADD CONSTRAINT knowledge_documents_current_version_fk
    FOREIGN KEY (tenant_id, current_published_version_id)
    REFERENCES knowledge_versions(tenant_id, id);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL,
    page_no INTEGER,
    section_title TEXT,
    embedding vector(1024),
    embedding_model VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, version_id) REFERENCES knowledge_versions(tenant_id, id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, version_id, chunk_index)
);

CREATE TABLE answer_citations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL,
    message_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    source_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, run_id) REFERENCES agent_runs(tenant_id, id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES messages(tenant_id, id),
    FOREIGN KEY (tenant_id, chunk_id) REFERENCES knowledge_chunks(tenant_id, id),
    UNIQUE (tenant_id, id)
);

CREATE TABLE ticket_citations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    ticket_id UUID NOT NULL,
    citation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets(tenant_id, id),
    FOREIGN KEY (tenant_id, citation_id) REFERENCES answer_citations(tenant_id, id),
    UNIQUE (tenant_id, ticket_id, citation_id)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    actor_id UUID,
    actor_role VARCHAR(32),
    trace_id VARCHAR(64) NOT NULL,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    result VARCHAR(16) NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    parameter_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, actor_id) REFERENCES users(tenant_id, id)
);
CREATE INDEX audit_events_tenant_trace_created_idx ON audit_events(tenant_id, trace_id, created_at DESC);
CREATE INDEX audit_events_tenant_resource_idx ON audit_events(tenant_id, resource_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO aftercare_app;
REVOKE UPDATE, DELETE ON audit_events FROM aftercare_app;
