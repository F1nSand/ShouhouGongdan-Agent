package com.aftercare.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderView> findByOrderNo(UUID tenantId, String orderNo) {
        return findOrder(tenantId, "o.order_no = ?", orderNo);
    }

    public Optional<OrderView> findById(UUID tenantId, UUID orderId) {
        return findOrder(tenantId, "o.id = ?", orderId);
    }

    private Optional<OrderView> findOrder(UUID tenantId, String condition, Object value) {
        var records = jdbc.query("""
                SELECT o.id, o.order_no, o.status, o.purchased_at, o.shipped_at,
                    o.paid_amount_cents, o.refunded_amount_cents, o.promised_delivery_at,
                    c.display_name, c.phone_masked
                FROM orders o
                JOIN customers c ON c.tenant_id = o.tenant_id AND c.id = o.customer_id
                WHERE o.tenant_id = ? AND %s
                """.formatted(condition), (rs, rowNum) -> new OrderRecord(
                rs.getObject("id", UUID.class), rs.getString("order_no"), rs.getString("status"),
                instant(rs.getTimestamp("purchased_at")), instant(rs.getTimestamp("shipped_at")),
                rs.getLong("paid_amount_cents"), rs.getLong("refunded_amount_cents"),
                instant(rs.getTimestamp("promised_delivery_at")), rs.getString("display_name"),
                rs.getString("phone_masked")), tenantId, value);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        OrderRecord order = records.getFirst();
        List<OrderView.Item> items = jdbc.query("""
                SELECT oi.sku_id, s.sku_code, s.name, oi.quantity, oi.unit_price_cents
                FROM order_items oi
                JOIN skus s ON s.tenant_id = oi.tenant_id AND s.id = oi.sku_id
                WHERE oi.tenant_id = ? AND oi.order_id = ?
                ORDER BY s.sku_code
                """, (rs, rowNum) -> new OrderView.Item(rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"), rs.getString("name"), rs.getInt("quantity"),
                rs.getLong("unit_price_cents")), tenantId, order.id());
        return Optional.of(new OrderView(order.id(), order.orderNo(), order.status(), order.purchasedAt(),
                order.shippedAt(), order.paidAmountCents(), order.refundedAmountCents(),
                order.promisedDeliveryAt(), order.customerName(), order.maskedPhone(), items));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record OrderRecord(UUID id, String orderNo, String status, Instant purchasedAt, Instant shippedAt,
            long paidAmountCents, long refundedAmountCents, Instant promisedDeliveryAt, String customerName,
            String maskedPhone) {
    }
}
