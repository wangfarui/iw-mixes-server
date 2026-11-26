package com.itwray.iw.external.zhaogang.statistics;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "iw.external.zhaogang.statistics-worker-enabled", havingValue = "true", matchIfMissing = true)
class StatisticsScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(StatisticsScheduler.class);
    private final StatisticsRepository repository;
    private final StatisticsBuilder builder;
    private final StatisticsService service;
    private final AtomicReference<StatisticsRepository.Task> running = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "devhub-statistics");
        thread.setDaemon(true);
        return thread;
    });

    StatisticsScheduler(StatisticsRepository repository, StatisticsBuilder builder, StatisticsService service) {
        this.repository = repository;
        this.builder = builder;
        this.service = service;
    }

    @Scheduled(fixedDelay = 1500)
    void dispatch() {
        long now = service.clock().millis();
        var active = running.get();
        if (active != null) {
            repository.heartbeat(active, now);
            return;
        }
        for (var candidate : repository.pending(now)) {
            var job = repository.claim(candidate, now);
            if (job == null) continue;
            running.set(job);
            worker.execute(() -> run(job));
            return;
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 15_000)
    void prepare() {
        var today = service.today();
        for (long org : repository.organizations()) {
            service.preparePresets(org, today);
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    void cleanup() {
        repository.cleanup(service.clock().millis() - Duration.ofDays(7).toMillis());
    }

    private void run(StatisticsRepository.Task job) {
        try {
            var today = service.today();
            if (!builder.build(job, today, service.retention(today), service.policy(today), service.clock())) {
                repository.retry(job, service.clock().millis(), true);
            }
        } catch (RuntimeException error) {
            LOG.warn("Dev Hub statistics build failed: org={}, range={}, job={}", job.org(), job.key(), job.jobId(), error);
            repository.retry(job, service.clock().millis(), false);
        } finally {
            running.compareAndSet(job, null);
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
