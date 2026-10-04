package com.aftercare.agent;

import java.util.Map;
import java.util.UUID;

import com.aftercare.approval.ConfirmedActionResult;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunActionResultPublisher {
    private final AgentRunRepository runs;
    private final RunEventService events;
    private final ObjectMapper objectMapper;

    public RunActionResultPublisher(AgentRunRepository runs, RunEventService events, ObjectMapper objectMapper) {
        this.runs = runs;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void confirmed(ActorContext actor, UUID runId, ConfirmedActionResult result) {
        if (runId == null) {
            return;
        }
        UUID conversationId = runs.conversationForOwnedRun(actor.tenantId(), actor.actorId(), runId)
                .orElseThrow(() -> new ApiException("RESOURCE_NOT_FOUND", "未找到该运行", HttpStatus.NOT_FOUND));
        String answer = result.actionId() == null
                ? "工单 " + result.ticketNo() + " 已创建，当前状态为 " + result.status() + "。"
                : "敏感操作申请已提交，当前状态为 " + result.status() + "，等待对应角色审批。";
        var structured = Map.of("answer", answer, "actionResult", result,
                "needsHuman", result.actionId() != null);
        runs.insertAssistantMessage(actor.tenantId(), conversationId, runId, answer, json(structured));
        events.appendSystem(actor.tenantId(), runId, "action.result", result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize action result", invalid);
        }
    }
}
