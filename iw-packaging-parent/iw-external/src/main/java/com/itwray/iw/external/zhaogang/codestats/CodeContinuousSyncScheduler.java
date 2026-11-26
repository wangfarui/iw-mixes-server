package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.AutomaticSyncTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class CodeContinuousSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CodeContinuousSyncScheduler.class);
    private final CodeStatsScopeDirectory directory;
    private final CodeScanService scans;
    private final ZhaogangProperties properties;
    private record Round(LocalDateTime scheduledAt, CodeRepositoryScanReads reads) {}

    private final Map<AutomaticSyncTarget, Round> pending = new LinkedHashMap<>();
    private int nextDiffTarget;
    private boolean gitUnavailableReported;

    CodeContinuousSyncScheduler(CodeStatsScopeDirectory directory, CodeScanService scans, ZhaogangProperties properties) {
        this.directory = directory;
        this.scans = scans;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeAfterStartup() {
        synchronize();
    }

    @Scheduled(cron = "0 0 */3 * * *", zone = "Asia/Shanghai")
    synchronized void synchronize() {
        if (!properties.isCodeSyncEnabled()) return;
        try {
            var round = new Round(LocalDateTime.now(CodeStatsSupport.CHINA_ZONE).withNano(0), new CodeRepositoryScanReads());
            directory.automaticTargets().forEach(target -> pending.putIfAbsent(target, round));
        } catch (RuntimeException error) {
            log.warn("Could not discover automatic code sync targets, error={}", error.getClass().getSimpleName());
        }
        dispatchPending();
    }

    // 只在有空闲扫描线程时提交自动任务，给页面触发的同步保留扫描队列。
    @Scheduled(fixedDelay = 5000)
    synchronized void dispatchPending() {
        if (!properties.isCodeSyncEnabled()) return;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && scans.hasScheduledSyncCapacity()) {
            var entry = iterator.next();
            var target = entry.getKey();
            try {
                var scope = directory.resolveAutomatic(target);
                var round = entry.getValue();
                if (scope.isPresent() && !scans.scheduledSync(scope.get(), round.scheduledAt(), round.reads())) continue;
            } catch (RuntimeException error) {
                log.warn("Automatic code sync deferred, scopeType={}, scopeId={}, error={}",
                        target.scope(), target.scopeId(), error.getClass().getSimpleName());
            }
            iterator.remove();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    synchronized void retryFailedDiffs() {
        if (!properties.isCodeSyncEnabled() || !properties.isCodeAutoDiffRetryEnabled()
                || !pending.isEmpty() || !scans.hasAutomaticDiffCapacity()) return;
        boolean gitAvailable = scans.gitRetryAvailable();
        if (properties.isCodeGitLineStatsEnabled() && !gitAvailable && !gitUnavailableReported) {
            log.warn("Automatic code diff Git recovery paused: configured Git executable is unavailable");
        }
        gitUnavailableReported = !gitAvailable;
        try {
            var targets = directory.automaticTargets();
            for (int checked = 0; checked < targets.size(); checked++) {
                int index = nextDiffTarget % targets.size();
                nextDiffTarget = (index + 1) % targets.size();
                var scope = directory.resolveAutomatic(targets.get(index));
                if (scope.isPresent() && scans.scheduledDiffRetry(scope.get(), gitAvailable)) break;
            }
        } catch (RuntimeException error) {
            log.warn("Automatic code diff retry deferred, error={}", error.getClass().getSimpleName());
        }
    }
}
