package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodeContinuousScheduleTest {
    @Test void schedulerIsThreeHourlyAndOnlyResolvesRegisteredScopesWithCurrentCredentials() throws Exception {
        var annotation=CodeContinuousSyncScheduler.class.getDeclaredMethod("synchronize").getAnnotation(Scheduled.class);
        assertEquals("0 0 */3 * * *",annotation.cron()); assertEquals("Asia/Shanghai",annotation.zone());
        var jobs=mock(ZhaogangCodeScanJobMapper.class);var credentials=mock(CodingCredentialService.class);
        var directory=mock(CodeStatsScopeDirectory.class);var scans=mock(CodeScanService.class);
        var subscription=new ScanJobEntity();subscription.setCodingTeamId(100L);subscription.setScopeType("SELF");subscription.setScopeId(1L);
        when(jobs.selectSyncSubscriptions()).thenReturn(List.of(subscription));when(credentials.token(100,1)).thenReturn(Optional.empty());
        var scheduler=new CodeContinuousSyncScheduler(jobs,credentials,directory,scans,new ZhaogangProperties());
        scheduler.synchronize();verifyNoInteractions(directory,scans);
        when(credentials.token(100,1)).thenReturn(Optional.of("fixture"));scheduler.synchronize();
        verify(directory).resolve(argThat(context->context.userId()==1 && context.codingTeamId()==100),eq(CodeStatsModels.Scope.SELF),isNull());
    }
    @Test void syncUsesJanuaryFloorRegardlessOfViewedMonth() {
        var jobs=mock(ZhaogangCodeScanJobMapper.class);var worker=mock(CodeScanWorker.class);var identities=mock(CodeIdentityResolver.class);
        Clock clock=Clock.fixed(Instant.parse("2026-11-03T02:00:00Z"),CodeStatsSupport.CHINA_ZONE);
        var scope=new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF,1,100,"team","https://example.com","self",List.of(new CodeStatsModule.MemberCredential(1,"self","","fixture")),false);
        var range=new CodeRange(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),List.of(YearMonth.of(2026,10)));
        var service=new CodeScanService(jobs,mock(ZhaogangCodeScanRepoMapper.class),mock(ZhaogangCodeCommitMapper.class),mock(CodeStatsViewCache.class),worker,new ZhaogangProperties(),identities,clock);
        try {
            assertTrue(service.triggerSync(scope,range,1).accepted());
            verify(jobs).insert(argThat((ScanJobEntity job)->"SYNC_V2".equals(job.getScanMode()) && LocalDate.parse("2026-01-01").equals(job.getSyncFrom())
                    && LocalDate.parse("2026-11-03").equals(job.getSyncTo()) && "100:SELF:1".equals(job.getActiveKey())));
            verify(worker,timeout(1000)).run(any());
        } finally {service.shutdown();}
    }
    @Test void startupResumesInterruptedSubscriptionButDoesNotRescanFreshCompletedHistory() {
        var jobs=mock(ZhaogangCodeScanJobMapper.class);var credentials=mock(CodingCredentialService.class);
        var directory=mock(CodeStatsScopeDirectory.class);var scans=mock(CodeScanService.class);
        var row=new ScanJobEntity();row.setCodingTeamId(100L);row.setScopeType("SELF");row.setScopeId(1L);row.setStatus("CANCELLED");
        when(jobs.selectSyncSubscriptions()).thenReturn(List.of(row));when(credentials.token(100,1)).thenReturn(Optional.of("fixture"));
        var scheduler=new CodeContinuousSyncScheduler(jobs,credentials,directory,scans,new ZhaogangProperties());
        scheduler.resumeAfterStartup();verify(scans).scheduledSync(any(),eq(row));
        clearInvocations(scans,credentials,directory);
        row.setStatus("COMPLETED");row.setFinishedAt(LocalDateTime.now(CodeStatsSupport.CHINA_ZONE));
        scheduler.resumeAfterStartup();verifyNoInteractions(scans,credentials,directory);
    }

}
