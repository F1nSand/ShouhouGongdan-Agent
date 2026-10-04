package com.aftercare.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CommandDedupService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CommandDedupService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> beginOrReplay(ActorContext actor, String commandName, String idempotencyKey,
            Object request) {
        validateKey(idempotencyKey);
        String requestHash = hash(request);
        jdbc.update("""
                INSERT INTO command_dedup(id, tenant_id, actor_id, command_name, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING')
                ON CONFLICT (tenant_id, actor_id, command_name, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), actor.tenantId(), actor.actorId(), commandName,
                idempotencyKey, requestHash);
        var rows = jdbc.query("""
                SELECT request_hash, result::text AS result_json, status FROM command_dedup
                WHERE tenant_id = ? AND actor_id = ? AND command_name = ? AND idempotency_key = ?
                FOR UPDATE
                """, (rs, rowNum) -> new DedupRecord(rs.getString("request_hash"),
                rs.getString("result_json"), rs.getString("status")),
                actor.tenantId(), actor.actorId(), commandName, idempotencyKey);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Could not claim an idempotent command");
        }
        var record = rows.getFirst();
        if (!MessageDigest.isEqual(record.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException("IDEMPOTENCY_CONFLICT", "幂等 Key 已用于不同的请求参数", HttpStatus.CONFLICT);
        }
        if ("COMPLETED".equals(record.status())) {
            if (record.resultJson() == null) {
                throw new IllegalStateException("Completed idempotent command has no stored result");
            }
            try {
                return Optional.of(objectMapper.readTree(record.resultJson()));
            }
            catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Stored idempotent result is invalid JSON", invalid);
            }
        }
        return Optional.empty();
    }

    public void complete(ActorContext actor, String commandName, String idempotencyKey, Object result) {
        int changed = jdbc.update("""
                UPDATE command_dedup SET status = 'COMPLETED', result = CAST(? AS jsonb), updated_at = now()
                WHERE tenant_id = ? AND actor_id = ? AND command_name = ? AND idempotency_key = ?
                  AND status = 'RUNNING'
                """, json(result), actor.tenantId(), actor.actorId(), commandName, idempotencyKey);
        if (changed != 1) {
            throw new IllegalStateException("Idempotent command was not in RUNNING state");
        }
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException("INVALID_ARGUMENT", "请提供有效的 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
    }

    private String hash(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request)));
        }
        catch (NoSuchAlgorithmException | JsonProcessingException unavailable) {
            throw new IllegalStateException("Could not hash idempotent command", unavailable);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize idempotent command result", invalid);
        }
    }

    private record DedupRecord(String requestHash, String resultJson, String status) {
    }
}
