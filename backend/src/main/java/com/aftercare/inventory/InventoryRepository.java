package com.aftercare.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StockRecord> findBySkuCode(UUID tenantId, String skuCode) {
        return jdbc.query("""
                SELECT s.id AS sku_id, s.sku_code, s.name AS sku_name, w.id AS warehouse_id,
                    w.code AS warehouse_code, w.name AS warehouse_name,
                    i.on_hand, i.reserved, i.safety_stock, i.version
                FROM skus s
                JOIN inventory i ON i.tenant_id = s.tenant_id AND i.sku_id = s.id
                JOIN warehouses w ON w.tenant_id = i.tenant_id AND w.id = i.warehouse_id
                WHERE s.tenant_id = ? AND s.sku_code = ? AND s.status = 'ACTIVE' AND w.status = 'ACTIVE'
                ORDER BY w.code
                """, (rs, rowNum) -> new StockRecord(rs.getObject("sku_id", UUID.class), rs.getString("sku_code"),
                rs.getString("sku_name"), rs.getObject("warehouse_id", UUID.class),
                rs.getString("warehouse_code"), rs.getString("warehouse_name"), rs.getInt("on_hand"),
                rs.getInt("reserved"), rs.getInt("safety_stock"), rs.getLong("version")), tenantId, skuCode);
    }

    public Optional<SkuRecord> findActiveSkuByCode(UUID tenantId, String skuCode) {
        return jdbc.query("""
                SELECT id, sku_code, name FROM skus WHERE tenant_id = ? AND sku_code = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> new SkuRecord(rs.getObject("id", UUID.class),
                rs.getString("sku_code"), rs.getString("name")), tenantId, skuCode).stream().findFirst();
    }

    public Optional<UUID> findActiveSkuId(UUID tenantId, UUID skuId) {
        return jdbc.query("SELECT id FROM skus WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
                (rs, rowNum) -> rs.getObject(1, UUID.class), tenantId, skuId).stream().findFirst();
    }

    public boolean activeWarehouseExists(UUID tenantId, UUID warehouseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM warehouses WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE')
                """, Boolean.class, tenantId, warehouseId));
    }

    public List<TransferRecommendationService.InventoryCandidate> findTransferCandidates(UUID tenantId, UUID skuId,
            UUID targetWarehouseId) {
        return jdbc.query("""
                SELECT i.warehouse_id, i.on_hand, i.reserved, i.safety_stock, i.version,
                    r.estimated_days, r.cost_cents_per_unit
                FROM inventory i
                JOIN warehouses source ON source.tenant_id = i.tenant_id AND source.id = i.warehouse_id
                JOIN warehouse_routes r ON r.tenant_id = i.tenant_id
                    AND r.source_warehouse_id = i.warehouse_id AND r.target_warehouse_id = ?
                JOIN warehouses target ON target.tenant_id = r.tenant_id AND target.id = r.target_warehouse_id
                WHERE i.tenant_id = ? AND i.sku_id = ?
                    AND source.status = 'ACTIVE' AND target.status = 'ACTIVE'
                """, (rs, rowNum) -> new TransferRecommendationService.InventoryCandidate(
                rs.getObject("warehouse_id", UUID.class), rs.getInt("on_hand"), rs.getInt("reserved"),
                rs.getInt("safety_stock"), rs.getLong("version"), rs.getInt("estimated_days"),
                rs.getLong("cost_cents_per_unit")), targetWarehouseId, tenantId, skuId);
    }

    public record StockRecord(UUID skuId, String skuCode, String skuName, UUID warehouseId,
            String warehouseCode, String warehouseName, int onHand, int reserved, int safetyStock,
            long inventoryVersion) {
    }

    public record SkuRecord(UUID id, String skuCode, String name) {
    }
}
