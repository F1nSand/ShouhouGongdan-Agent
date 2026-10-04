package com.aftercare.agent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aftercare.knowledge.PolicyEvidence;
import org.springframework.stereotype.Component;

@Component
public class RunEvidenceRegistry {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<PolicyEvidence.Chunk>> evidence =
            new ConcurrentHashMap<>();

    public void record(UUID runId, PolicyEvidence policyEvidence) {
        if (runId == null || policyEvidence == null || policyEvidence.chunks().isEmpty()) {
            return;
        }
        var chunks = evidence.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        for (var chunk : policyEvidence.chunks()) {
            if (chunks.stream().noneMatch(existing -> existing.chunkId().equals(chunk.chunkId()))) {
                chunks.add(chunk);
            }
        }
    }

    public PolicyEvidence snapshot(UUID runId) {
        return new PolicyEvidence(List.copyOf(evidence.getOrDefault(runId, new CopyOnWriteArrayList<>())));
    }

    public void clear(UUID runId) {
        evidence.remove(runId);
    }
}
