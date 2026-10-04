package com.aftercare.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import com.aftercare.approval.ActionDraftService;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentRunService {
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<Role> INTERNAL_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);

    private final AgentRunRepository repository;
    private final RunEventService events;
    private final AgentOrchestrator orchestrator;
    private final ActionDraftService drafts;
    private final TaskExecutor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final boolean recoveryEnabled;

    public AgentRunService(AgentRunRepository repository, RunEventService events,
            AgentOrchestrator orchestrator, ActionDraftService drafts,
            @Qualifier("agentTaskExecutor") TaskExecutor executor, Clock clock, ObjectMapper objectMapper,
            @Value("${app.agent.recovery-enabled:true}") boolean recoveryEnabled) {
        this.repository = repository;
        this.events = events;
        this.orchestrator = orchestrator;
        this.drafts = drafts;
        this.executor = executor;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.recoveryEnabled = recoveryEnabled;
    }

    @Transactional
    public AgentRunView start(ActorContext actor, UUID conversationId, UUID requestId, String rawMessage) {
        if (!INTERNAL_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能启动 Agent 运行", HttpStatus.FORBIDDEN);
        }
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (conversationId == null || requestId == null || message.isBlank() || message.length() > 4000) {
            throw new ApiException("INVALID_ARGUMENT", "会话、requestId 和不超过 4000 字的问题内容为必填项",
                    HttpStatus.BAD_REQUEST);
        }
        if (!repository.lockOwnedConversation(actor.tenantId(), actor.actorId(), conversationId)) {
            throw new ApiException("RESOURCE_NOT_FOUND", "未找到该会话", HttpStatus.NOT_FOUND);
        }
        String inputHash = hash(message);
        var previous = repository.findRequest(actor.tenantId(), actor.actorId(), conversationId, requestId);
        if (previous.isPresent()) {
            if (!inputHash.equals(inputHashForRun(actor.tenantId(), previous.get().id()))) {
                throw new ApiException("IDEMPOTENCY_CONFLICT", "requestId 已用于不同的问题内容", HttpStatus.CONFLICT);
            }
            return view(previous.get());
        }
        if (repository.hasActiveRun(actor.tenantId(), conversationId)) {
            throw new ApiException("RUN_ALREADY_ACTIVE", "该会话已有运行中的请求", HttpStatus.TOO_MANY_REQUESTS);
        }

        UUID runId = UUID.randomUUID();
        var deadline = clock.instant().plus(RUN_TIMEOUT);
        repository.insertRun(actor.tenantId(), runId, conversationId, actor.actorId(), requestId,
                inputHash, deadline, actor.traceId());
        repository.insertUserMessage(actor.tenantId(), conversationId, runId, message);
        events.appendSystem(actor.tenantId(), runId, "run.started",
                Map.of("runId", runId, "conversationId", conversationId, "status", "QUEUED"));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(actor, conversationId, runId, message);
                }
            });
        }
        else {
            dispatch(actor, conversationId, runId, message);
        }
        return new AgentRunView(runId, conversationId, "QUEUED", deadline, null, null,
                "/api/v1/agent-runs/" + runId + "/events");
    }

    public AgentRunView get(ActorContext actor, UUID runId) {
        return repository.findRun(actor.tenantId(), actor.actorId(), runId)
                .map(this::view).orElseThrow(() -> new ApiException(
                        "RESOURCE_NOT_FOUND", "未找到该运行", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public AgentRunView cancel(ActorContext actor, UUID runId) {
        var run = repository.findRun(actor.tenantId(), actor.actorId(), runId)
                .orElseThrow(() -> new ApiException("RESOURCE_NOT_FOUND", "未找到该运行", HttpStatus.NOT_FOUND));
        if (repository.cancelRun(actor.tenantId(), runId) != 1) {
            return view(run);
        }
        drafts.invalidateRun(actor, runId);
        events.appendSystem(actor.tenantId(), runId, "run.cancelled", Map.of("runId", runId));
        return get(actor, runId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        if (!recoveryEnabled) {
            return;
        }
        for (var run : repository.unfinishedRuns()) {
            if (repository.updateStatus(run.tenantId(), run.id(), "FAILED", "SERVER_RESTARTED") == 1) {
                events.appendSystem(run.tenantId(), run.id(), "run.failed",
                        Map.of("errorCode", "SERVER_RESTARTED"));
            }
        }
    }

    private void dispatch(ActorContext actor, UUID conversationId, UUID runId, String message) {
        try {
            executor.execute(() -> orchestrator.run(actor, conversationId, runId, message));
        }
        catch (RejectedExecutionException overloaded) {
            if (repository.failRun(actor.tenantId(), runId, "EXECUTOR_BUSY") == 1) {
                events.appendSystem(actor.tenantId(), runId, "run.failed", Map.of("errorCode", "EXECUTOR_BUSY"));
            }
        }
    }

    private AgentRunView view(AgentRunRepository.RunRecord run) {
        JsonNode answer = run.answerJson() == null ? null : readJson(run.answerJson());
        return new AgentRunView(run.id(), run.conversationId(), run.status(), run.deadlineAt(),
                run.errorCode(), answer, "/api/v1/agent-runs/" + run.id() + "/events");
    }

    private String inputHashForRun(UUID tenantId, UUID runId) {
        return repository.inputHash(tenantId, runId).orElse("");
    }

    private UUID conversationId(ActorContext actor, UUID runId) {
        return repository.conversationForOwnedRun(actor.tenantId(), actor.actorId(), runId)
                .orElseThrow(() -> new ApiException("RESOURCE_NOT_FOUND", "未找到该运行", HttpStatus.NOT_FOUND));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored run answer is invalid", invalid);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize run answer", invalid);
        }
    }
}
