package com.aftercare.jobs;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import com.aftercare.approval.ActionExecutor;
import com.aftercare.approval.SensitiveActionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Component
public class OutboxWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository outbox;
    private final ActionExecutor executor;
    private final SensitiveActionService actions;
    private final Clock clock;
    private final boolean enabled;
    private final String workerId = "action-worker-" + UUID.randomUUID();
    private final ScheduledExecutorService leaseRenewer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "action-outbox-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public OutboxWorker(OutboxRepository outbox, ActionExecutor executor, SensitiveActionService actions,
            Clock clock, @Value("${app.action-worker.enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.executor = executor;
        this.actions = actions;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.action-worker.delay:1000}")
    public void poll() {
        if (enabled) {
            processOne();
        }
    }

    public boolean processOne() {
        var maybeJob = outbox.claim(workerId, clock.instant());
        if (maybeJob.isEmpty()) {
            return false;
        }
        var job = maybeJob.get();
        var renewal = leaseRenewer.scheduleAtFixedRate(() -> {
            if (!outbox.renew(job.id(), workerId, job.leaseVersion(), clock.instant())) {
                LOGGER.warn("Sensitive action outbox lease was lost; actionId={}", job.aggregateId());
            }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            executor.execute(job.tenantId(), job.aggregateId());
            outbox.complete(job.id(), workerId, job.leaseVersion());
        }
        catch (RuntimeException failure) {
            String errorCode = failure instanceof com.aftercare.common.ApiException api
                    ? api.code() : "EXECUTION_TEMPORARY_FAILURE";
            var disposition = outbox.rescheduleOrFail(job, errorCode, clock.instant());
            if (disposition.leaseOwned() && !disposition.retryScheduled()) {
                actions.markExecutionFailed(job.tenantId(), job.aggregateId(), errorCode);
            }
            LOGGER.warn("Sensitive action outbox execution failed; actionId={}, retryScheduled={}, errorCode={}",
                    job.aggregateId(), disposition.retryScheduled(), errorCode);
        }
        finally {
            renewal.cancel(false);
        }
        return true;
    }

    @PreDestroy
    public void shutdown() {
        leaseRenewer.shutdownNow();
    }
}
