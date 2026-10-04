package com.aftercare.approval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.order.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Records refunds against fictional orders in the local demo database; it never contacts a payment provider. */
@Component
public class LocalDemoRefundAdapter {
    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final ObjectMapper objectMapper;

    public LocalDemoRefundAdapter(JdbcTemplate jdbc, OrderRepository orders, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    public JsonNode record(UUID tenantId, UUID actionId, JsonNode payload) {
        UUID orderId = RefundSimulationAdapter.uuid(payload, "orderId");
        long amount = RefundSimulationAdapter.longValue(payload, "amountCents");
        if (amount <= 0) {
            throw invalidAmount();
        }

        List<LedgerEntry> existing = jdbc.query("""
                SELECT order_id, amount_cents, refunded_total_cents
                FROM refund_ledger WHERE tenant_id = ? AND action_id = ?
                """, (rs, rowNum) -> new LedgerEntry(rs.getObject("order_id", UUID.class),
                rs.getLong("amount_cents"), rs.getLong("refunded_total_cents")), tenantId, actionId);
        if (!existing.isEmpty()) {
            LedgerEntry entry = existing.getFirst();
            if (!entry.orderId().equals(orderId) || entry.amountCents() != amount) {
                throw new IllegalStateException("Refund ledger action does not match its immutable payload");
            }
            return result(orderId, amount, entry.refundedTotalCents());
        }

        List<Long> totals = jdbc.query("""
                UPDATE orders
                SET refunded_amount_cents = refunded_amount_cents + ?, version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND paid_amount_cents - refunded_amount_cents >= ?
                RETURNING refunded_amount_cents
                """, (rs, rowNum) -> rs.getLong(1), amount, tenantId, orderId, amount);
        if (totals.isEmpty()) {
            if (orders.findById(tenantId, orderId).isEmpty()) {
                throw new ApiException("ORDER_NOT_FOUND", "退款订单不存在", HttpStatus.CONFLICT);
            }
            throw invalidAmount();
        }
        long refundedTotal = totals.getFirst();
        jdbc.update("""
                INSERT INTO refund_ledger(id, tenant_id, order_id, action_id, amount_cents,
                    refunded_total_cents, execution_mode)
                VALUES (?, ?, ?, ?, ?, ?, 'LOCAL_DEMO_LEDGER')
                """, UUID.randomUUID(), tenantId, orderId, actionId, amount, refundedTotal);
        return result(orderId, amount, refundedTotal);
    }

    private JsonNode result(UUID orderId, long amount, long total) {
        return objectMapper.valueToTree(Map.of("executionMode", "LOCAL_DEMO_LEDGER", "orderId", orderId,
                "amountCents", amount, "refundedTotalCents", total, "externalPayment", false,
                "message", "演示订单退款已入账；仅更新本地虚构订单账本，未调用外部支付系统"));
    }

    private static ApiException invalidAmount() {
        return new ApiException("REFUND_AMOUNT_INVALID", "退款金额超过当前可退款金额", HttpStatus.CONFLICT);
    }

    private record LedgerEntry(UUID orderId, long amountCents, long refundedTotalCents) {
    }
}
