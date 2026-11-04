package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 只调度显式启用的范围，重启后从数据库恢复；不会枚举所有凭证或隐式启用其他用户。 */
@Component
class CodeContinuousSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(CodeContinuousSyncScheduler.class);
    private final ZhaogangCodeScanJobMapper jobs;
    private final CodingCredentialService credentials;
    private final CodeStatsScopeDirectory directory;
    private final CodeScanService scans;
    private final ZhaogangProperties properties;

    CodeContinuousSyncScheduler(ZhaogangCodeScanJobMapper jobs, CodingCredentialService credentials,
                                CodeStatsScopeDirectory directory, CodeScanService scans, ZhaogangProperties properties) {
        this.jobs=jobs; this.credentials=credentials; this.directory=directory; this.scans=scans; this.properties=properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeAfterStartup() {
        if (!properties.isCodeSyncEnabled()) return;
        var now=java.time.LocalDateTime.now(CodeStatsSupport.CHINA_ZONE);
        for (var job:jobs.selectSyncSubscriptions()) {
            if ("RUNNING".equals(job.getStatus()) || "QUEUED".equals(job.getStatus())) continue;
            if ("COMPLETED".equals(job.getStatus()) && job.getFinishedAt()!=null && job.getFinishedAt().plusHours(3).isAfter(now)) continue;
            dispatch(job);
        }
    }

    @Scheduled(cron = "0 0 */3 * * *", zone = "Asia/Shanghai")
    void synchronize() {
        if (!properties.isCodeSyncEnabled()) return;
        var subscriptions=jobs.selectSyncSubscriptions();
        log.info("zhaogang three-hour sync tick, subscriptions={}",subscriptions.size());
        for (var job:subscriptions) dispatch(job);
    }

    private void dispatch(com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity job) {
        try {
            long actor = Scope.SELF.name().equals(job.getScopeType()) ? job.getScopeId() : job.getRequestedBy();
            String token = credentials.token(job.getCodingTeamId(), actor).orElse("");
            if (token.isBlank()) return;
            var context = new CodeStatsModule.Context(token, actor, "", "", job.getCodingTeamId(),job.getSyncTeamKey(),job.getSyncTeamHost());
            var scope = Scope.valueOf(job.getScopeType());
            scans.scheduledSync(directory.resolve(context, scope, scope == Scope.SELF ? null : job.getScopeId()), job);
        } catch (RuntimeException error) {
            log.warn("continuous code sync deferred, scopeType={}, scopeId={}, error={}",job.getScopeType(),job.getScopeId(),error.getClass().getSimpleName());
        }

    }
}
