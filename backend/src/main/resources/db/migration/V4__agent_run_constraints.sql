CREATE UNIQUE INDEX answer_citations_run_message_chunk_uq
    ON answer_citations(tenant_id, run_id, message_id, chunk_id);

CREATE INDEX run_events_replay_idx
    ON run_events(tenant_id, run_id, sequence);

CREATE INDEX agent_runs_active_conversation_idx
    ON agent_runs(tenant_id, conversation_id, status)
    WHERE status IN ('QUEUED', 'RUNNING');
