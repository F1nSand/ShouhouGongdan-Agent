package com.aftercare.agent;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Service
public class RunEventService {
    private static final Set<String> TERMINAL_EVENTS = Set.of(
            "answer.final", "run.completed", "run.failed", "run.cancelled");

    private final JdbcTemplate jdbc;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RunEventService(JdbcTemplate jdbc, AgentRunRepository runs, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public VisibleRunEvent appendSystem(UUID tenantId, UUID runId, String eventType, Object payload) {
        if (eventType == null || eventType.isBlank() || eventType.length() > 64) {
            throw new IllegalArgumentException("A visible event type is required");
        }
        var runExists = jdbc.query("SELECT id FROM agent_runs WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId, runId);
        if (runExists.isEmpty()) {
            throw notFound();
        }
        long sequence = runs.latestSequence(tenantId, runId) + 1;
        JsonNode body = objectMapper.valueToTree(payload == null ? objectMapper.createObjectNode() : payload);
        jdbc.update("""
                INSERT INTO run_events(id, tenant_id, run_id, sequence, event_type, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), tenantId, runId, sequence, eventType, json(body));
        return new VisibleRunEvent(runId, sequence, eventType, body, clock.instant());
    }

    public Flux<ServerSentEvent<VisibleRunEvent>> events(ActorContext actor, UUID runId, long afterSequence) {
        if (afterSequence < 0) {
            throw invalidCursor();
        }
        if (runs.conversationForOwnedRun(actor.tenantId(), actor.actorId(), runId).isEmpty()) {
            throw notFound();
        }
        if (afterSequence > runs.latestSequence(actor.tenantId(), runId)) {
            throw invalidCursor();
        }

        var cursor = new AtomicLong(afterSequence);
        return Flux.interval(Duration.ZERO, Duration.ofMillis(350))
                .map(tick -> poll(actor.tenantId(), runId, cursor.get(), tick))
                .doOnNext(batch -> {
                    if (batch.lastSequence() > cursor.get()) {
                        cursor.set(batch.lastSequence());
                    }
                })
                .takeUntil(PollBatch::terminal)
                .concatMapIterable(batch -> batch.events().isEmpty()
                        ? batch.heartbeat()
                                ? List.of(ServerSentEvent.<VisibleRunEvent>builder().comment("heartbeat").build())
                                : List.of()
                        : batch.events().stream().map(this::toSse).toList());
    }

    private PollBatch poll(UUID tenantId, UUID runId, long afterSequence, long tick) {
        List<VisibleRunEvent> found = runs.eventsAfter(tenantId, runId, afterSequence, 100);
        boolean terminalEvent = found.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.eventType()));
        boolean terminal = terminalEvent || runs.runIsTerminal(tenantId, runId);
        long lastSequence = found.isEmpty() ? afterSequence : found.getLast().sequence();
        return new PollBatch(found, terminal, found.isEmpty() && tick > 0 && tick % 43 == 0, lastSequence);
    }

    private ServerSentEvent<VisibleRunEvent> toSse(VisibleRunEvent event) {
        return ServerSentEvent.<VisibleRunEvent>builder(event)
                .id(Long.toString(event.sequence()))
                .event(event.eventType())
                .build();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize visible event", invalid);
        }
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该运行", HttpStatus.NOT_FOUND);
    }

    private static ApiException invalidCursor() {
        return new ApiException("INVALID_EVENT_CURSOR", "事件游标无效", HttpStatus.BAD_REQUEST);
    }

    private record PollBatch(List<VisibleRunEvent> events, boolean terminal, boolean heartbeat, long lastSequence) {
    }
}
