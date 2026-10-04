package com.aftercare.demo;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.sql.Timestamp;

import com.aftercare.identity.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
public class DemoSeedService implements ApplicationRunner {
    private static final String PRIMARY_MERCHANT = "DEMO_MERCHANT";
    private static final String SECONDARY_MERCHANT = "SECONDARY_MERCHANT";
    private static final String[] WAREHOUSE_CODES = {"WH-EAST", "WH-CENTRAL", "WH-SOUTH"};

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String csrPassword;
    private final String supervisorPassword;
    private final String warehousePassword;
    private final String adminPassword;

    public DemoSeedService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transaction,
            Clock clock,
            @Value("${DEMO_CSR_PASSWORD}") String csrPassword,
            @Value("${DEMO_SUPERVISOR_PASSWORD}") String supervisorPassword,
            @Value("${DEMO_WAREHOUSE_PASSWORD}") String warehousePassword,
            @Value("${DEMO_ADMIN_PASSWORD}") String adminPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
        this.clock = clock;
        this.csrPassword = csrPassword;
        this.supervisorPassword = supervisorPassword;
        this.warehousePassword = warehousePassword;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(status -> {
            UUID primaryTenant = ensureTenant(PRIMARY_MERCHANT, "售后演示商家");
            UUID secondaryTenant = ensureTenant(SECONDARY_MERCHANT, "隔离演示商家");
            seedUsers(primaryTenant, true);
            seedUsers(secondaryTenant, false);
            seedCatalog(primaryTenant);
            seedCatalog(secondaryTenant);
            seedOrders(primaryTenant, 100, "AF");
            seedOrders(secondaryTenant, 1, "MB");
        });
    }

    private UUID ensureTenant(String code, String name) {
        UUID candidate = id("tenant:" + code);
        jdbc.update("""
                INSERT INTO tenants(id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')
                ON CONFLICT (code) DO NOTHING
                """, candidate, code, name);
        return jdbc.queryForObject("SELECT id FROM tenants WHERE code = ?", UUID.class, code);
    }

    private void seedUsers(UUID tenantId, boolean fullRoles) {
        seedUser(tenantId, "csr", Role.CUSTOMER_SERVICE, csrPassword);
        if (fullRoles) {
            seedUser(tenantId, "supervisor", Role.SERVICE_SUPERVISOR, supervisorPassword);
            seedUser(tenantId, "warehouse", Role.WAREHOUSE_OPERATOR, warehousePassword);
            seedUser(tenantId, "admin", Role.ADMIN, adminPassword);
        }
    }

    private void seedUser(UUID tenantId, String username, Role role, String password) {
        jdbc.update("""
                INSERT INTO users(id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (tenant_id, username)
                DO UPDATE SET password_hash = EXCLUDED.password_hash
                """, id("user:" + tenantId + ":" + username), tenantId, username,
                passwordEncoder.encode(password), role.name());
    }

    private void seedCatalog(UUID tenantId) {
        for (int index = 1; index <= 8; index++) {
            String suffix = String.format("%02d", index);
            jdbc.update("""
                    INSERT INTO customers(id, tenant_id, display_name, phone_masked, external_ref)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, external_ref) DO NOTHING
                    """, id("customer:" + tenantId + ":" + index), tenantId,
                    "演示客户 " + suffix, "138****" + String.format("%04d", index), "CUS-" + suffix);
        }

        UUID[] skus = new UUID[20];
        for (int index = 1; index <= skus.length; index++) {
            String code = String.format("SKU-%03d", index);
            skus[index - 1] = id("sku:" + tenantId + ":" + code);
            jdbc.update("""
                    INSERT INTO skus(id, tenant_id, sku_code, name, category_code, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, sku_code) DO NOTHING
                    """, skus[index - 1], tenantId, code, "售后演示商品 " + index,
                    index % 2 == 0 ? "ELECTRONICS" : "HOME");
        }

        UUID[] warehouses = new UUID[WAREHOUSE_CODES.length];
        String[] names = {"华东中心仓", "华中周转仓", "华南备货仓"};
        String[] regions = {"EAST", "CENTRAL", "SOUTH"};
        for (int index = 0; index < WAREHOUSE_CODES.length; index++) {
            warehouses[index] = id("warehouse:" + tenantId + ":" + WAREHOUSE_CODES[index]);
            jdbc.update("""
                    INSERT INTO warehouses(id, tenant_id, code, name, region_code, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id, code) DO NOTHING
                    """, warehouses[index], tenantId, WAREHOUSE_CODES[index], names[index], regions[index]);
        }

        for (int source = 0; source < warehouses.length; source++) {
            for (int target = 0; target < warehouses.length; target++) {
                if (source != target) {
                    jdbc.update("""
                            INSERT INTO warehouse_routes(id, tenant_id, source_warehouse_id, target_warehouse_id,
                                estimated_days, cost_cents_per_unit)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, source_warehouse_id, target_warehouse_id) DO NOTHING
                            """, id("route:" + tenantId + ":" + source + ":" + target), tenantId,
                            warehouses[source], warehouses[target], 1 + Math.abs(source - target),
                            180L + 70L * Math.abs(source - target));
                }
            }
        }

        for (int skuIndex = 0; skuIndex < skus.length; skuIndex++) {
            for (int warehouseIndex = 0; warehouseIndex < warehouses.length; warehouseIndex++) {
                int onHand = 20 + ((skuIndex * 7 + warehouseIndex * 13) % 40);
                int safetyStock = 2 + ((skuIndex + warehouseIndex) % 4);
                jdbc.update("""
                        INSERT INTO inventory(id, tenant_id, warehouse_id, sku_id, on_hand, reserved, safety_stock)
                        VALUES (?, ?, ?, ?, ?, 0, ?)
                        ON CONFLICT (tenant_id, warehouse_id, sku_id) DO NOTHING
                        """, id("inventory:" + tenantId + ":" + warehouseIndex + ":" + skuIndex), tenantId,
                        warehouses[warehouseIndex], skus[skuIndex], onHand, safetyStock);
            }
        }
    }

    private void seedOrders(UUID tenantId, int orderCount, String prefix) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (int index = 1; index <= orderCount; index++) {
            String orderNo = prefix + "20260926" + String.format("%03d", index);
            UUID orderId = id("order:" + tenantId + ":" + orderNo);
            int customerIndex = (index - 1) % 8 + 1;
            int skuIndex = (index - 1) % 20 + 1;
            Instant purchasedAt = now.minus(Duration.ofDays(1 + index % 18));
            boolean shipped = index % 3 != 0;
            boolean delivered = index % 5 == 0;
            String orderStatus = delivered ? "DELIVERED" : shipped ? "SHIPPED" : "PAID";
            Instant shippedAt = shipped ? purchasedAt.plus(Duration.ofDays(1)) : null;
            Instant promised = purchasedAt.plus(Duration.ofDays(5));
            long paidAmount = 5900L + (index % 11) * 1250L;
            jdbc.update("""
                    INSERT INTO orders(id, tenant_id, order_no, customer_id, status, purchased_at, shipped_at,
                        paid_amount_cents, refunded_amount_cents, promised_delivery_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    ON CONFLICT (tenant_id, order_no) DO NOTHING
                    """, orderId, tenantId, orderNo, id("customer:" + tenantId + ":" + customerIndex),
                    orderStatus, timestamp(purchasedAt), timestamp(shippedAt), paidAmount, timestamp(promised));

            UUID skuId = id("sku:" + tenantId + ":SKU-" + String.format("%03d", skuIndex));
            jdbc.update("""
                    INSERT INTO order_items(id, tenant_id, order_id, sku_id, quantity, unit_price_cents)
                    VALUES (?, ?, ?, ?, 1, ?)
                    ON CONFLICT (tenant_id, order_id, sku_id) DO NOTHING
                    """, id("order-item:" + orderId), tenantId, orderId, skuId, paidAmount);

            if (shippedAt != null) {
                String trackingNo = "TRK" + String.format("%010d", index);
                jdbc.update("""
                        INSERT INTO logistics_events(id, tenant_id, order_id, source_code, external_event_id,
                            carrier_code, tracking_no, event_code, event_time, description)
                        VALUES (?, ?, ?, 'DEMO', ?, 'DEMO_CARRIER', ?, 'PICKED_UP', ?, '包裹已揽收')
                        ON CONFLICT (tenant_id, source_code, external_event_id) DO NOTHING
                        """, id("logistics:" + orderId + ":pickup"), tenantId, orderId,
                        orderNo + ":pickup", trackingNo, timestamp(shippedAt));
                Instant latestAt = delivered ? purchasedAt.plus(Duration.ofDays(4))
                        : shippedAt.plus(Duration.ofDays(index % 7 == 0 ? 4 : 1));
                String eventCode = delivered ? "DELIVERED" : "IN_TRANSIT";
                String eventDescription = delivered ? "包裹已签收" : "包裹运输中";
                jdbc.update("""
                        INSERT INTO logistics_events(id, tenant_id, order_id, source_code, external_event_id,
                            carrier_code, tracking_no, event_code, event_time, description)
                        VALUES (?, ?, ?, 'DEMO', ?, 'DEMO_CARRIER', ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, source_code, external_event_id) DO NOTHING
                        """, id("logistics:" + orderId + ":latest"), tenantId, orderId,
                        orderNo + ":latest", trackingNo, eventCode, timestamp(latestAt), eventDescription);
            }
        }
    }

    private UUID id(String key) {
        return UUID.nameUUIDFromBytes(("aftercare-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
