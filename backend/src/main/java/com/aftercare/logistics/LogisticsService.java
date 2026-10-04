package com.aftercare.logistics;

import java.time.Clock;
import java.util.UUID;

import com.aftercare.order.OrderRepository;
import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LogisticsService {
    private final OrderRepository orderRepository;
    private final LogisticsRepository logisticsRepository;
    private final Clock clock;

    public LogisticsService(OrderRepository orderRepository, LogisticsRepository logisticsRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.logisticsRepository = logisticsRepository;
        this.clock = clock;
    }

    public LogisticsView queryLogistics(ActorContext actor, UUID orderId) {
        var order = orderRepository.findById(actor.tenantId(), orderId)
                .orElseThrow(() -> new ApiException("RESOURCE_NOT_FOUND", "未找到该订单", HttpStatus.NOT_FOUND));
        var events = logisticsRepository.findEvents(actor.tenantId(), orderId);
        var calculatedAt = clock.instant();
        var assessment = LogisticsRules.evaluate(order.status(), order.shippedAt(), order.promisedDeliveryAt(),
                events.stream().map(event -> new LogisticsRules.Event(event.eventCode(), event.eventTime())).toList(),
                calculatedAt);
        return new LogisticsView(orderId, calculatedAt, assessment.lastUpdateAt(), assessment.dataInsufficient(),
                assessment.reasons().stream().map(Enum::name).toList(),
                events.stream().map(event -> new LogisticsView.Event(event.eventCode(), event.eventTime(),
                        event.carrierCode(), event.trackingNo(), event.description())).toList());
    }
}
