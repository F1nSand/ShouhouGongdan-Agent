package com.aftercare.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(UUID orderId, String orderNo, String status, Instant purchasedAt, Instant shippedAt,
        long paidAmountCents, long refundedAmountCents, Instant promisedDeliveryAt, String customerName,
        String maskedPhone, List<Item> items) {
    public OrderView {
        items = List.copyOf(items);
    }

    public record Item(UUID skuId, String skuCode, String name, int quantity, long unitPriceCents) {
    }
}
