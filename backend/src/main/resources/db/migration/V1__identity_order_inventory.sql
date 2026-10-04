CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id)
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    username VARCHAR(64) NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, username)
);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    display_name TEXT NOT NULL,
    phone_masked VARCHAR(32),
    external_ref VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, external_ref)
);

CREATE TABLE skus (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    sku_code VARCHAR(64) NOT NULL,
    name TEXT NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sku_code)
);

CREATE TABLE warehouses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(64) NOT NULL,
    name TEXT NOT NULL,
    region_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    order_no VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    purchased_at TIMESTAMPTZ NOT NULL,
    shipped_at TIMESTAMPTZ,
    paid_amount_cents BIGINT NOT NULL CHECK (paid_amount_cents >= 0),
    refunded_amount_cents BIGINT NOT NULL DEFAULT 0 CHECK (refunded_amount_cents >= 0),
    promised_delivery_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_no),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id),
    CHECK (refunded_amount_cents <= paid_amount_cents)
);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    order_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    FOREIGN KEY (tenant_id, sku_id) REFERENCES skus(tenant_id, id),
    UNIQUE (tenant_id, order_id, sku_id)
);

CREATE TABLE logistics_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    order_id UUID NOT NULL,
    source_code VARCHAR(64) NOT NULL,
    external_event_id VARCHAR(128) NOT NULL,
    carrier_code VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(64) NOT NULL,
    event_code VARCHAR(32) NOT NULL CHECK (event_code IN ('PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'EXCEPTION')),
    event_time TIMESTAMPTZ NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    UNIQUE (tenant_id, source_code, external_event_id)
);
CREATE INDEX logistics_events_order_time_idx ON logistics_events(tenant_id, order_id, event_time DESC);

CREATE TABLE warehouse_routes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    source_warehouse_id UUID NOT NULL,
    target_warehouse_id UUID NOT NULL,
    estimated_days INTEGER NOT NULL CHECK (estimated_days >= 0),
    cost_cents_per_unit BIGINT NOT NULL CHECK (cost_cents_per_unit >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, source_warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, target_warehouse_id) REFERENCES warehouses(tenant_id, id),
    CHECK (source_warehouse_id <> target_warehouse_id),
    UNIQUE (tenant_id, source_warehouse_id, target_warehouse_id)
);

CREATE TABLE inventory (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    warehouse_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    safety_stock INTEGER NOT NULL DEFAULT 0 CHECK (safety_stock >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, sku_id) REFERENCES skus(tenant_id, id),
    CHECK (reserved <= on_hand),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, warehouse_id, sku_id)
);

CREATE TABLE transfer_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    action_id UUID NOT NULL,
    source_warehouse_id UUID NOT NULL,
    target_warehouse_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'CANCELLED', 'EXPIRED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, action_id),
    FOREIGN KEY (tenant_id, source_warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, target_warehouse_id) REFERENCES warehouses(tenant_id, id),
    FOREIGN KEY (tenant_id, sku_id) REFERENCES skus(tenant_id, id),
    CHECK (source_warehouse_id <> target_warehouse_id)
);

CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    inventory_id UUID NOT NULL,
    transfer_order_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, inventory_id) REFERENCES inventory(tenant_id, id),
    FOREIGN KEY (tenant_id, transfer_order_id) REFERENCES transfer_orders(tenant_id, id),
    UNIQUE (tenant_id, transfer_order_id)
);

CREATE INDEX orders_tenant_customer_idx ON orders(tenant_id, customer_id, purchased_at DESC);
CREATE INDEX inventory_tenant_sku_idx ON inventory(tenant_id, sku_id, warehouse_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aftercare_app') THEN
        CREATE ROLE aftercare_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO aftercare_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO aftercare_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aftercare_app;
