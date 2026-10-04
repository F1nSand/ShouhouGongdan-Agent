package com.aftercare.jobs;

import java.time.Clock;
import java.time.Instant;

import com.aftercare.approval.ActionDraftRepository;
import com.aftercare.approval.SensitiveActionService;
import com.aftercare.inventory.TransferOrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpirationWorker {
    private final SensitiveActionService actions;
    private final ActionDraftRepository drafts;
    private final TransferOrderService transfers;
    private final Clock clock;
    private final boolean enabled;

    public ExpirationWorker(SensitiveActionService actions, ActionDraftRepository drafts,
            TransferOrderService transfers, Clock clock,
            @Value("${app.action-worker.enabled:true}") boolean enabled) {
        this.actions = actions;
        this.drafts = drafts;
        this.transfers = transfers;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.expiration-worker.delay:60000}")
    public void poll() {
        if (enabled) {
            expire(clock.instant());
        }
    }

    public ExpirationSummary expire(Instant now) {
        int actionsExpired = actions.expire(now);
        int draftsExpired = drafts.expireUnconfirmed(now);
        int reservationsReleased = transfers.releaseExpiredReservations(now);
        return new ExpirationSummary(actionsExpired, draftsExpired, reservationsReleased);
    }

    public record ExpirationSummary(int actionsExpired, int draftsExpired, int reservationsReleased) {
    }
}
