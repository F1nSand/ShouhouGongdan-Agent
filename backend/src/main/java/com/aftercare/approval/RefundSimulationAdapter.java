package com.aftercare.approval;

import java.util.Map;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.order.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RefundSimulationAdapter {
    private final OrderRepository orders;
    private final ObjectMapper objectMapper;

    public RefundSimulationAdapter(OrderRepository orders, ObjectMapper objectMapper) {
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    public JsonNode simulate(UUID tenantId, JsonNode payload) {
        UUID orderId = uuid(payload, "orderId");
        long amount = longValue(payload, "amountCents");
        var order = orders.findById(tenantId, orderId).orElseThrow(() ->
                new ApiException("ORDER_NOT_FOUND", "退款订单不存在", HttpStatus.CONFLICT));
        long refundable = order.paidAmountCents() - order.refundedAmountCents();
        if (amount <= 0 || amount > refundable) {
            throw new ApiException("REFUND_AMOUNT_INVALID", "退款金额超过当前可退款金额", HttpStatus.CONFLICT);
        }
        return objectMapper.valueToTree(Map.of("simulation", true, "orderId", orderId,
                "orderNo", order.orderNo(), "amountCents", amount,
                "message", "模拟退款完成；支付系统与订单退款金额未修改"));
    }

    static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new ApiException("INVALID_REFUND_PAYLOAD", "退款申请内容无效", HttpStatus.CONFLICT);
        }
        try {
            return UUID.fromString(value.asText());
        }
        catch (IllegalArgumentException invalid) {
            throw new ApiException("INVALID_REFUND_PAYLOAD", "退款申请内容无效", HttpStatus.CONFLICT);
        }
    }

    static long longValue(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new ApiException("INVALID_REFUND_PAYLOAD", "退款申请内容无效", HttpStatus.CONFLICT);
        }
        return value.longValue();
    }
}
