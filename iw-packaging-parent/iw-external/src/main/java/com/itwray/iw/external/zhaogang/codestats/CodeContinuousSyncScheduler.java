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
    private final Map<AutomaticSyncTarget, LocalDateTime> pending = new LinkedHashMap<>();

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
            LocalDateTime scheduledAt = LocalDateTime.now(CodeStatsSupport.CHINA_ZONE).withNano(0);
            directory.automaticTargets().forEach(target -> pending.putIfAbsent(target, scheduledAt));
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
                if (scope.isPresent() && !scans.scheduledSync(scope.get(), entry.getValue())) continue;
            } catch (RuntimeException error) {
                log.warn("Automatic code sync deferred, scopeType={}, scopeId={}, error={}",
                        target.scope(), target.scopeId(), error.getClass().getSimpleName());
            }
            iterator.remove();
        }
    }
}
