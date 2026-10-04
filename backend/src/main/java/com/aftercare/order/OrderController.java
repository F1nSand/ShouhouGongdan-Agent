package com.aftercare.order;

import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class OrderController {
    private final OrderService orderService;
    private final CurrentActorService currentActorService;

    public OrderController(OrderService orderService, CurrentActorService currentActorService) {
        this.orderService = orderService;
        this.currentActorService = currentActorService;
    }

    @GetMapping
    public ApiResponse<OrderView> byOrderNo(@RequestParam String orderNo) {
        return ApiResponse.ok(orderService.query(currentActorService.currentActor(), orderNo));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> byId(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderService.query(currentActorService.currentActor(), orderId));
    }
}
