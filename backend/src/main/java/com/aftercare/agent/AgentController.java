package com.aftercare.agent;

import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class AgentController {
    private final ConversationService conversations;
    private final AgentRunService runs;
    private final RunEventService events;
    private final CurrentActorService currentActor;

    public AgentController(ConversationService conversations, AgentRunService runs,
            RunEventService events, CurrentActorService currentActor) {
        this.conversations = conversations;
        this.runs = runs;
        this.events = events;
        this.currentActor = currentActor;
    }

    @GetMapping("/conversations")
    public ApiResponse<ConversationService.Page<AgentRunRepository.ConversationSummary>> listConversations(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(conversations.list(currentActor.currentActor(), page, size));
    }

    @PostMapping("/conversations")
    public ResponseEntity<ApiResponse<ConversationService.ConversationView>> createConversation(
            @Valid @RequestBody CreateConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(conversations.create(currentActor.currentActor(), request.title())));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<java.util.List<AgentRunRepository.MessageView>> messages(
            @PathVariable UUID conversationId, @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(conversations.messages(currentActor.currentActor(), conversationId, limit));
    }

    @PostMapping("/conversations/{conversationId}/runs")
    public ResponseEntity<ApiResponse<AgentRunView>> start(@PathVariable UUID conversationId,
            @Valid @RequestBody RunRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(runs.start(currentActor.currentActor(),
                conversationId, request.requestId(), request.message())));
    }

    @GetMapping("/agent-runs/{runId}")
    public ApiResponse<AgentRunView> get(@PathVariable UUID runId) {
        return ApiResponse.ok(runs.get(currentActor.currentActor(), runId));
    }

    @GetMapping(value = "/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<VisibleRunEvent>>> events(@PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(defaultValue = "0") long afterSequence) {
        long cursor = lastEventId == null || lastEventId.isBlank()
                ? afterSequence : parseCursor(lastEventId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body(events.events(currentActor.currentActor(), runId, cursor));
    }

    @PostMapping("/agent-runs/{runId}/cancel")
    public ApiResponse<AgentRunView> cancel(@PathVariable UUID runId) {
        return ApiResponse.ok(runs.cancel(currentActor.currentActor(), runId));
    }

    private static long parseCursor(String value) {
        try {
            long cursor = Long.parseLong(value);
            if (cursor < 0) {
                throw new NumberFormatException();
            }
            return cursor;
        }
        catch (NumberFormatException invalid) {
            throw new ApiException("INVALID_EVENT_CURSOR", "事件游标无效", HttpStatus.BAD_REQUEST);
        }
    }

    public record CreateConversationRequest(@Size(max = 120) String title) {
    }

    public record RunRequest(@NotNull UUID requestId, @Size(min = 1, max = 4000) String message) {
    }
}
