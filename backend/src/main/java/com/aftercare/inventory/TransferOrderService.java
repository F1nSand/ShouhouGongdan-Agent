package com.aftercare.inventory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.aftercare.approval.ExecutionResult;
import com.aftercare.approval.SensitiveActionRepository;
import com.aftercare.audit.AuditService;
import com.aftercare.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferOrderService {
    private static final Duration RESERVATION_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final SensitiveActionRepository actions;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransferOrderService(JdbcTemplate jdbc, SensitiveActionRepository actions,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.actions = actions;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TransferOrderView reserveForApprovedAction(UUID tenantId, UUID actionId) {
        var action = actions.findForUpdate(tenantId, actionId)
                .orElseThrow(TransferOrderService::notFound);
        Optional<TransferOrderView> existing = findByAction(tenantId, actionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!"STOCK_TRANSFER".equals(action.type())) {
            throw new ApiException("ACTION_TYPE_MISMATCH", "申请不是库存调拨", HttpStatus.CONFLICT);
        }
        if (!"APPROVED".equals(action.status()) && !"EXECUTING".equals(action.status())) {
            throw new ApiException("ACTION_NOT_APPROVED", "申请尚未批准", HttpStatus.CONFLICT);
        }
        if (!"EXECUTING".equals(action.status()) && !action.expiresAt().isAfter(clock.instant())) {
            actions.markExpired(tenantId, actionId);
            throw new ApiException("ACTION_EXPIRED", "调拨申请已过期", HttpStatus.GONE);
        }
        if ("APPROVED".equals(action.status()) && actions.markExecuting(tenantId, actionId) != 1) {
            throw new ApiException("ACTION_STATE_CONFLICT", "申请状态已变化", HttpStatus.CONFLICT);
        }

        JsonNode payload = read(action.payloadJson());
        UUID skuId = uuid(payload, "skuId");
        UUID sourceWarehouseId = uuid(payload, "sourceWarehouseId");
        UUID targetWarehouseId = uuid(payload, "targetWarehouseId");
        int quantity = positiveInt(payload, "quantity");
        if (skuId == null || sourceWarehouseId == null || targetWarehouseId == null
                || sourceWarehouseId.equals(targetWarehouseId) || quantity <= 0) {
            return fail(tenantId, actionId, "INVALID_TRANSFER_PAYLOAD");
        }

        boolean routeAvailable = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM skus s
                    JOIN warehouses src ON src.tenant_id = s.tenant_id AND src.id = ? AND src.status = 'ACTIVE'
                    JOIN warehouses dst ON dst.tenant_id = s.tenant_id AND dst.id = ? AND dst.status = 'ACTIVE'
                    JOIN warehouse_routes route ON route.tenant_id = s.tenant_id
                        AND route.source_warehouse_id = src.id AND route.target_warehouse_id = dst.id
                    WHERE s.tenant_id = ? AND s.id = ? AND s.status = 'ACTIVE'
                )
                """, Boolean.class, sourceWarehouseId, targetWarehouseId, tenantId, skuId));
        if (!routeAvailable) {
            return fail(tenantId, actionId, "TRANSFER_ROUTE_UNAVAILABLE");
        }

        var inventoryRows = jdbc.query("""
                UPDATE inventory SET reserved = reserved + ?, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND warehouse_id = ? AND sku_id = ?
                  AND on_hand - reserved - safety_stock >= ?
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                quantity, tenantId, sourceWarehouseId, skuId, quantity);
        if (inventoryRows.isEmpty()) {
            return fail(tenantId, actionId, "INSUFFICIENT_ALLOCATABLE_STOCK");
        }

        UUID transferId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(RESERVATION_TTL);
        jdbc.update("""
                INSERT INTO transfer_orders(id, tenant_id, action_id, source_warehouse_id,
                    target_warehouse_id, sku_id, quantity, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                """, transferId, tenantId, actionId, sourceWarehouseId, targetWarehouseId, skuId, quantity);
        jdbc.update("""
                INSERT INTO inventory_reservations(id, tenant_id, inventory_id, transfer_order_id,
                    quantity, status, expires_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, UUID.randomUUID(), tenantId, inventoryRows.getFirst(), transferId,
                quantity, Timestamp.from(expiresAt));
        var result = new ExecutionResult(actionId, "SUCCEEDED", false, transferId, null, null, null);
        if (actions.markSucceeded(tenantId, actionId, "SUCCEEDED", json(result)) != 1) {
            throw new IllegalStateException("Approved transfer action changed before reservation completed");
        }
        auditService.systemSuccess(tenantId, "INVENTORY_RESERVED", "TRANSFER_ORDER", transferId,
                Map.of("actionId", actionId, "sourceWarehouseId", sourceWarehouseId,
                        "targetWarehouseId", targetWarehouseId, "skuId", skuId, "quantity", quantity,
                        "reservationExpiresAt", expiresAt));
        return findByAction(tenantId, actionId).orElseThrow(() ->
                new IllegalStateException("Transfer order was not persisted"));
    }

    @Transactional
    public int releaseExpiredReservations(Instant now) {
        var expired = jdbc.query("""
                SELECT r.id AS reservation_id, r.tenant_id, r.inventory_id, r.transfer_order_id, r.quantity
                FROM inventory_reservations r
                WHERE r.status = 'ACTIVE' AND r.expires_at <= ?
                ORDER BY r.expires_at, r.id
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new ExpiredReservation(rs.getObject("reservation_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("inventory_id", UUID.class),
                rs.getObject("transfer_order_id", UUID.class), rs.getInt("quantity")), Timestamp.from(now));
        int released = 0;
        for (var reservation : expired) {
            int changed = jdbc.update("""
                    UPDATE inventory_reservations SET status = 'RELEASED', updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), reservation.tenantId(), reservation.id());
            if (changed == 0) {
                continue;
            }
            int stockChanged = jdbc.update("""
                    UPDATE inventory SET reserved = reserved - ?, version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND reserved >= ?
                    """, reservation.quantity(), Timestamp.from(now), reservation.tenantId(),
                    reservation.inventoryId(), reservation.quantity());
            if (stockChanged != 1) {
                throw new IllegalStateException("Active reservation exceeds inventory reserved quantity");
            }
            jdbc.update("""
                    UPDATE transfer_orders SET status = 'EXPIRED', version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND status = 'RESERVED'
                    """, Timestamp.from(now), reservation.tenantId(), reservation.transferOrderId());
            auditService.systemSuccess(reservation.tenantId(), "INVENTORY_RESERVATION_RELEASED",
                    "TRANSFER_ORDER", reservation.transferOrderId(),
                    Map.of("reservationId", reservation.id(), "quantity", reservation.quantity()));
            released++;
        }
        return released;
    }

    private Optional<TransferOrderView> findByAction(UUID tenantId, UUID actionId) {
        return jdbc.query("""
                SELECT t.id, t.action_id, t.source_warehouse_id, t.target_warehouse_id, t.sku_id,
                    t.quantity, t.status, r.expires_at
                FROM transfer_orders t JOIN inventory_reservations r
                  ON r.tenant_id = t.tenant_id AND r.transfer_order_id = t.id
                WHERE t.tenant_id = ? AND t.action_id = ?
                """, (rs, rowNum) -> new TransferOrderView(rs.getObject("id", UUID.class),
                rs.getObject("action_id", UUID.class), rs.getObject("source_warehouse_id", UUID.class),
                rs.getObject("target_warehouse_id", UUID.class), rs.getObject("sku_id", UUID.class),
                rs.getInt("quantity"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()),
                tenantId, actionId).stream().findFirst();
    }

    private TransferOrderView fail(UUID tenantId, UUID actionId, String code) {
        var result = new ExecutionResult(actionId, "FAILED", false, null, code, null, null);
        if (actions.markFailed(tenantId, actionId, code, json(result)) == 1) {
            auditService.systemFailure(tenantId, "SENSITIVE_ACTION_EXECUTION_FAILED", "SENSITIVE_ACTION",
                    actionId, Map.of("errorCode", code), code);
        }
        throw new ApiException(code, "调拨条件发生变化，未预留库存", HttpStatus.CONFLICT);
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JsonProcessingException invalid) {
            throw new ApiException("INVALID_TRANSFER_PAYLOAD", "调拨申请内容无效", HttpStatus.CONFLICT);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Could not serialize transfer result", invalid);
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        }
        catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.canConvertToInt() ? -1 : value.intValue();
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该调拨申请", HttpStatus.NOT_FOUND);
    }

    private record ExpiredReservation(UUID id, UUID tenantId, UUID inventoryId,
            UUID transferOrderId, int quantity) {
    }
}
