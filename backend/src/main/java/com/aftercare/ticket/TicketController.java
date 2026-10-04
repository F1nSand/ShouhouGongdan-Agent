package com.aftercare.ticket;

import java.util.UUID;

import com.aftercare.common.ApiResponse;
import com.aftercare.identity.CurrentActorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
@PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN')")
public class TicketController {
    private final TicketService service;
    private final CurrentActorService currentActorService;

    public TicketController(TicketService service, CurrentActorService currentActorService) {
        this.service = service;
        this.currentActorService = currentActorService;
    }

    @GetMapping
    public ApiResponse<TicketService.TicketPage> list(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(currentActorService.currentActor(), status, orderId, page, size));
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<TicketView> get(@PathVariable UUID ticketId) {
        return ApiResponse.ok(service.get(currentActorService.currentActor(), ticketId));
    }

    @PostMapping("/{ticketId}/assign")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN')")
    public ApiResponse<TicketView> assign(@PathVariable UUID ticketId,
            @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(service.assign(currentActorService.currentActor(), ticketId,
                request.assigneeId(), request.expectedVersion()));
    }

    @PostMapping("/{ticketId}/transition")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN')")
    public ApiResponse<TicketView> transition(@PathVariable UUID ticketId,
            @Valid @RequestBody TransitionRequest request) {
        return ApiResponse.ok(service.transition(currentActorService.currentActor(), ticketId,
                request.toStatus(), request.comment(), request.expectedVersion()));
    }

    public record AssignRequest(@NotNull UUID assigneeId, @PositiveOrZero long expectedVersion) {
    }

    public record TransitionRequest(@NotNull TicketStatus toStatus,
            @Size(max = 5000) String comment, @PositiveOrZero long expectedVersion) {
    }
}
