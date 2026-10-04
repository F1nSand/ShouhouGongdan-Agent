package com.aftercare.order;

import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public OrderView query(ActorContext actor, String orderNo) {
        return repository.findByOrderNo(actor.tenantId(), orderNo)
                .orElseThrow(OrderService::notFound);
    }

    public OrderView query(ActorContext actor, UUID orderId) {
        return repository.findById(actor.tenantId(), orderId)
                .orElseThrow(OrderService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "未找到该订单", HttpStatus.NOT_FOUND);
    }
}
