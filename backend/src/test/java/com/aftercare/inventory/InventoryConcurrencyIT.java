package com.aftercare.inventory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aftercare.identity.Role;
import com.aftercare.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.action-worker.enabled=false", "app.knowledge.worker-enabled=false"})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryConcurrencyIT {
    private static final String JWT_SECRET = "bG9jYWwtZGVtby1qd3Qtc2VjcmV0LW5vdC1mb3ItcHJvZA==";

    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aftercare")
            .withUsername("aftercare_migrator")
            .withPassword("local-migration-password");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("app.security.jwt-secret", () -> JWT_SECRET);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransferOrderService transferOrders;

    private UUID tenantId;
    private UUID requesterId;
    private UUID skuId;
    private UUID sourceWarehouseId;
    private UUID targetWarehouseId;

    @BeforeEach
    void insertInventoryFixture() {
        tenantId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        sourceWarehouseId = UUID.randomUUID();
        targetWarehouseId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id, code, name, status) VALUES (?, ?, 'Inventory test', 'ACTIVE')",
                tenantId, "INVENTORY_" + tenantId.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, 'unused', ?, 'ACTIVE')
                """, requesterId, tenantId, "requester_" + requesterId.toString().substring(0, 8),
                Role.CUSTOMER_SERVICE.name());
        jdbc.update("""
                INSERT INTO skus(id, tenant_id, sku_code, name, category_code, status)
                VALUES (?, ?, 'SKU-1', 'Test item', 'GENERAL', 'ACTIVE')
                """, skuId, tenantId);
        jdbc.update("""
                INSERT INTO warehouses(id, tenant_id, code, name, region_code, status)
                VALUES (?, ?, 'WH-SOURCE', 'Source', 'EAST', 'ACTIVE'),
                       (?, ?, 'WH-TARGET', 'Target', 'WEST', 'ACTIVE')
                """, sourceWarehouseId, tenantId, targetWarehouseId, tenantId);
        jdbc.update("""
                INSERT INTO warehouse_routes(id, tenant_id, source_warehouse_id, target_warehouse_id,
                    estimated_days, cost_cents_per_unit)
                VALUES (?, ?, ?, ?, 2, 100)
                """, UUID.randomUUID(), tenantId, sourceWarehouseId, targetWarehouseId);
        jdbc.update("""
                INSERT INTO inventory(id, tenant_id, warehouse_id, sku_id, on_hand, reserved, safety_stock)
                VALUES (?, ?, ?, ?, 10, 0, 2), (?, ?, ?, ?, 0, 0, 0)
                """, UUID.randomUUID(), tenantId, sourceWarehouseId, skuId,
                UUID.randomUUID(), tenantId, targetWarehouseId, skuId);
    }

    @Test
    void twentyReservationsKeepSafetyStock() throws Exception {
        var actionIds = new ArrayList<UUID>();
        for (int index = 0; index < 20; index++) {
            actionIds.add(createApprovedTransferAction());
        }
        var ready = new CountDownLatch(actionIds.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(actionIds.size())) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (UUID actionId : actionIds) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        transferOrders.reserveForApprovedAction(tenantId, actionId);
                        return true;
                    }
                    catch (ApiException expectedConflict) {
                        if (!"INSUFFICIENT_ALLOCATABLE_STOCK".equals(expectedConflict.code())) {
                            throw expectedConflict;
                        }
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(8);
        }
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE tenant_id = ? AND warehouse_id = ?",
                Integer.class, tenantId, sourceWarehouseId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_orders WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenantId)).isEqualTo(8);
    }

    @Test
    void expiredReservationIsReleasedOnlyOnce() {
        UUID actionId = createApprovedTransferAction();
        var transfer = transferOrders.reserveForApprovedAction(tenantId, actionId);
        Instant now = Instant.now().plusSeconds(2);
        jdbc.update("UPDATE inventory_reservations SET expires_at = ? WHERE tenant_id = ? AND transfer_order_id = ?",
                Timestamp.from(now.minusSeconds(1)), tenantId, transfer.id());

        assertThat(transferOrders.releaseExpiredReservations(now)).isEqualTo(1);
        assertThat(transferOrders.releaseExpiredReservations(now)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory WHERE tenant_id = ? AND warehouse_id = ?",
                Integer.class, tenantId, sourceWarehouseId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE tenant_id = ? AND transfer_order_id = ?",
                String.class, tenantId, transfer.id())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_orders WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, transfer.id())).isEqualTo("EXPIRED");
    }

    private UUID createApprovedTransferAction() {
        UUID draftId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        String payload = "{\"skuId\":\"" + skuId + "\",\"sourceWarehouseId\":\"" + sourceWarehouseId
                + "\",\"targetWarehouseId\":\"" + targetWarehouseId
                + "\",\"quantity\":1,\"suggestedInventoryVersion\":0,\"recommendationSnapshot\":{}}";
        jdbc.update("""
                INSERT INTO action_drafts(id, tenant_id, origin, created_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, 'MANUAL', ?, 'REQUEST_STOCK_TRANSFER', CAST(? AS jsonb), ?, '{}'::jsonb, 'CONFIRMED', ?)
                """, draftId, tenantId, requesterId, payload, "b".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO sensitive_actions(id, tenant_id, draft_id, requested_by, type, payload, payload_hash,
                    evidence_snapshot, status, expires_at)
                VALUES (?, ?, ?, ?, 'STOCK_TRANSFER', CAST(? AS jsonb), ?, '{}'::jsonb, 'APPROVED', ?)
                """, actionId, tenantId, draftId, requesterId, payload, "b".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        return actionId;
    }
}
