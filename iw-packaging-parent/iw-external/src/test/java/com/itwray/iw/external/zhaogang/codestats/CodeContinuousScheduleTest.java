package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.AutomaticSyncTarget;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeContinuousScheduleTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T02:00:00Z"), CodeStatsSupport.CHINA_ZONE);

    @Test
    void startupDiscoversBothScopesWithoutPreviousJobsAndDrainsWhenCapacityReturns() {
        var directory = mock(CodeStatsScopeDirectory.class);
        var scans = mock(CodeScanService.class);
        var personal = new AutomaticSyncTarget(Scope.SELF, 100, 1);
        var team = new AutomaticSyncTarget(Scope.WORKBENCH_TEAM, 100, 8);
        var personalScope = scope(personal.scope(), personal.scopeId());
        var teamScope = scope(team.scope(), team.scopeId());
        when(directory.automaticTargets()).thenReturn(List.of(personal, team));
        when(directory.resolveAutomatic(personal)).thenReturn(Optional.of(personalScope));
        when(directory.resolveAutomatic(team)).thenReturn(Optional.of(teamScope));
        when(scans.hasScheduledSyncCapacity()).thenReturn(true, false, true);
        when(scans.scheduledSync(eq(personalScope), any())).thenReturn(true);
        when(scans.scheduledSync(eq(teamScope), any())).thenReturn(true);

        var scheduler = new CodeContinuousSyncScheduler(directory, scans, new ZhaogangProperties());
        scheduler.resumeAfterStartup();
        verify(scans).scheduledSync(eq(personalScope), any());
        verify(scans, never()).scheduledSync(eq(teamScope), any());

        scheduler.dispatchPending();
        verify(scans).scheduledSync(eq(teamScope), any());
    }

    @Test
    void scheduledTickKeepsOriginalCronAndRechecksCredentialsAtDispatch() throws Exception {
        var annotation = CodeContinuousSyncScheduler.class.getDeclaredMethod("synchronize").getAnnotation(Scheduled.class);
        assertEquals("0 0 */3 * * *", annotation.cron());
        assertEquals("Asia/Shanghai", annotation.zone());
        var directory = mock(CodeStatsScopeDirectory.class);
        var scans = mock(CodeScanService.class);
        var revoked = new AutomaticSyncTarget(Scope.SELF, 100, 1);
        var newTeam = new AutomaticSyncTarget(Scope.WORKBENCH_TEAM, 100, 8);
        var teamScope = scope(newTeam.scope(), newTeam.scopeId());
        when(directory.automaticTargets()).thenReturn(List.of(revoked, newTeam));
        when(directory.resolveAutomatic(revoked)).thenReturn(Optional.empty());
        when(directory.resolveAutomatic(newTeam)).thenReturn(Optional.of(teamScope));
        when(scans.hasScheduledSyncCapacity()).thenReturn(true);
        when(scans.scheduledSync(eq(teamScope), any())).thenReturn(false, true);

        var scheduler = new CodeContinuousSyncScheduler(directory, scans, new ZhaogangProperties());
        scheduler.synchronize();
        verify(scans).scheduledSync(eq(teamScope), any());
        verify(scans, never()).scheduledSync(eq(scope(revoked.scope(), revoked.scopeId())), any());
        scheduler.dispatchPending();
        verify(scans, times(2)).scheduledSync(eq(teamScope), any());
    }

    @Test
    void manualSyncReusesExistingBaselineAndCanRunRightAfterAutomaticSync() {
        var jobs = mock(ZhaogangCodeScanJobMapper.class);
        var worker = mock(CodeScanWorker.class);
        var personal = scope(Scope.SELF, 1);
        var viewedRange = new CodeRange(LocalDate.parse("2025-11-01"), LocalDate.parse("2025-11-30"),
                List.of(YearMonth.of(2025, 11)));
        var latest = new ScanJobEntity();
        latest.setRequestedMode("AUTO");
        latest.setStatus("COMPLETED");
        latest.setFinishedAt(LocalDateTime.now(clock).minusSeconds(1));
        latest.setSyncFrom(LocalDate.parse("2025-12-01"));
        latest.setSyncTo(LocalDate.now(clock));
        when(jobs.selectLatestSync(100L, "SELF", 1L)).thenReturn(latest);

        var service = service(jobs, worker);
        try {
            assertTrue(service.triggerSync(personal, viewedRange, 1).accepted());
            verify(jobs).insert(argThat((ScanJobEntity job) -> "SYNC_V2".equals(job.getScanMode())
                    && "MANUAL".equals(job.getRequestedMode())
                    && latest.getSyncFrom().equals(job.getSyncFrom())
                    && LocalDate.now(clock).equals(job.getSyncTo())));
            verify(worker, timeout(1000)).run(any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void eligibleScopeReportsNextSyncBeforeItsFirstRunAndAutomaticSyncBuildsBaseline() {
        var jobs = mock(ZhaogangCodeScanJobMapper.class);
        var worker = mock(CodeScanWorker.class);
        var team = scope(Scope.WORKBENCH_TEAM, 8);
        var range = new CodeRange(LocalDate.parse("2026-11-01"), LocalDate.now(clock), List.of(YearMonth.of(2026, 11)));

        var service = service(jobs, worker);
        try {
            var sync = service.scanState(team, range).sync();
            assertTrue(sync.enabled());
            assertEquals("NOT_STARTED", sync.status());
            assertEquals("2026-11-03T12:00:00+08:00", sync.nextSyncAt());
            assertTrue(service.scheduledSync(team, LocalDateTime.now(clock)));
            verify(jobs).insert(argThat((ScanJobEntity job) -> "AUTO".equals(job.getRequestedMode())
                    && "100:WORKBENCH_TEAM:8".equals(job.getActiveKey())
                    && LocalDate.parse("2026-01-01").equals(job.getSyncFrom())));
            verify(worker, timeout(1000)).run(any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void automaticSyncWaitsForAnActiveJobAndRetriesIfItWasInterrupted() {
        var jobs = mock(ZhaogangCodeScanJobMapper.class);
        var worker = mock(CodeScanWorker.class);
        var personal = scope(Scope.SELF, 1);
        var active = new ScanJobEntity();
        when(jobs.selectByActiveKey("100:SELF:1")).thenReturn(active, null);
        var scheduledAt = LocalDateTime.now(clock).minusMinutes(1);

        var service = service(jobs, worker);
        try {
            assertFalse(service.scheduledSync(personal, scheduledAt));
            verify(jobs, never()).insert(any(ScanJobEntity.class));

            active.setStatus("INTERRUPTED");
            active.setFinishedAt(LocalDateTime.now(clock));
            active.setSyncFrom(LocalDate.parse("2025-12-01"));
            when(jobs.selectLatestSync(100L, "SELF", 1L)).thenReturn(active);
            assertTrue(service.scheduledSync(personal, scheduledAt));
            verify(jobs).insert(argThat((ScanJobEntity job) -> active.getSyncFrom().equals(job.getSyncFrom())));
        } finally {
            service.shutdown();
        }
    }

    private CodeScanService service(ZhaogangCodeScanJobMapper jobs, CodeScanWorker worker) {
        return new CodeScanService(jobs, mock(ZhaogangCodeScanRepoMapper.class), mock(ZhaogangCodeCommitMapper.class),
                mock(CodeStatsViewCache.class), worker, new ZhaogangProperties(), mock(CodeIdentityResolver.class), clock);
    }

    private CodeStatsModule.ScopeInfo scope(Scope scope, long scopeId) {
        return new CodeStatsModule.ScopeInfo(scope, scopeId, 100, "team", "https://team.coding.net", "fixture",
                List.of(new CodeStatsModule.MemberCredential(1, "fixture", "", "fixture-token")), false);
    }
}
