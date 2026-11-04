package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodeScanRangeStatusTest {
    final ZhaogangCodeScanJobMapper jobs=mock(ZhaogangCodeScanJobMapper.class);
    final ZhaogangCodeScanRepoMapper repos=mock(ZhaogangCodeScanRepoMapper.class);
    final ZhaogangCodeCommitMapper commits=mock(ZhaogangCodeCommitMapper.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-04T03:10:00Z"),CodeStatsSupport.CHINA_ZONE);
    final CodeStatsModule.ScopeInfo scope=new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF,1,100,"t","https://example.com","self",
            List.of(new CodeStatsModule.MemberCredential(1,"Self","","fixture")),false);

    ScanJobEntity job() {
        var job=new ScanJobEntity();job.setId(41L);job.setCodingTeamId(100L);job.setScopeType("SELF");job.setScopeId(1L);
        job.setMonth("2026-11");job.setScanMode("SYNC_V2");job.setSyncFrom(LocalDate.parse("2026-01-01"));job.setSyncTo(LocalDate.parse("2026-11-04"));
        job.setStatus("PARTIAL");job.setStage("DONE");job.setFailedRepos(0);job.setFailedDiffs(1);job.setTruncated(false);
        job.setErrorCode("PARTIAL_SCAN");job.setErrorMessage("扫描完成，但存在失败仓库或未完整统计的 diff");
        job.setFinishedAt(LocalDateTime.parse("2026-11-04T11:00:00"));return job;
    }
    CodeScanService service(ScanJobEntity job) {
        when(jobs.selectLatest(anyLong(),anyString(),anyLong(),anyString())).thenReturn(job);
        when(jobs.selectLatestSuccessful(anyLong(),anyString(),anyLong(),anyString())).thenReturn(job);
        when(jobs.selectLatestSync(anyLong(),anyString(),anyLong())).thenReturn(job);
        when(jobs.selectById(41L)).thenReturn(job);
        return new CodeScanService(jobs,repos,commits,mock(CodeStatsViewCache.class),mock(CodeScanWorker.class),new ZhaogangProperties(),mock(CodeIdentityResolver.class),clock);
    }
    @Test void historicalDiffFailureMustNotMarkCompleteNovemberAsPartial() {
        var service=service(job());
        try {
            var state=service.scanState(scope,CodeRange.parse("2026-11-01","2026-11-04",24,clock));
            assertEquals("COMPLETED",state.aggregateStatus());assertNull(state.months().get(0).error());
            assertEquals("PARTIAL",state.sync().status());
        } finally {service.shutdown();}
    }

    @Test void failingJulyRemainsPartialAndUsesExactRequestedDates() {
        var service=service(job());
        try {
            when(commits.countIncompleteDiffs(100,"SELF",1,LocalDate.parse("2026-07-10"),LocalDate.parse("2026-07-20"))).thenReturn(1L);
            var state=service.scanState(scope,CodeRange.parse("2026-07-10","2026-07-20",24,clock));
            assertEquals("PARTIAL",state.aggregateStatus());assertTrue(state.months().get(0).error().contains("1 条"));
        } finally {service.shutdown();}
    }
    @Test void globalFailureDetailsIncludeHistoricalMonthsAndDates() {
        var service=service(job());
        var row=new com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity();
        row.setSha("july");row.setDepotName("skills");row.setDiffStatus("FAILED");row.setCommitAt(LocalDateTime.parse("2026-07-02T10:00:00"));
        when(commits.selectDiffFailures(100,"SELF",1,LocalDate.parse("2026-01-01"),LocalDate.parse("2026-11-04"))).thenReturn(List.of(row));
        try {
            var failures=service.failures(scope,41,1,20);
            assertEquals("2026-01-01",failures.from());assertEquals("2026-11-04",failures.to());
            assertEquals(1,failures.page().totalRow());assertEquals("july",failures.diffFailures().get(0).sha());
            assertTrue(failures.diffFailures().get(0).commitAt().startsWith("2026-07-02"));
        } finally {service.shutdown();}
    }
    @Test void rangeStatusCannotConcealMetadataFailureOrTruncation() {
        for(boolean truncated:List.of(false,true)) {
            var row=job();row.setFailedRepos(truncated?0:1);row.setTruncated(truncated);var service=service(row);
            try {assertEquals("PARTIAL",service.scanState(scope,CodeRange.parse("2026-11-01","2026-11-04",24,clock)).aggregateStatus());}
            finally {service.shutdown();}
        }
    }
    @Test void activeSyncStillShowsRunningAndIsNotDeclaredComplete() {
        var row=job();row.setStatus("RUNNING");var service=service(row);
        try {assertEquals("RUNNING",service.scanState(scope,CodeRange.parse("2026-11-01","2026-11-04",24,clock)).aggregateStatus());}
        finally {service.shutdown();}
    }
    @Test void retainedTooLargeDiffStillMarksItsRangeIncompleteAfterSuccessfulIncrement() {
        var row=job();row.setStatus("COMPLETED");row.setFailedDiffs(0);var service=service(row);
        when(commits.countIncompleteDiffs(100,"SELF",1,LocalDate.parse("2026-08-01"),LocalDate.parse("2026-08-31"))).thenReturn(2L);
        try {assertEquals("PARTIAL",service.scanState(scope,CodeRange.parse("2026-08-01","2026-08-31",24,clock)).aggregateStatus());}
        finally {service.shutdown();}
    }
    @Test void oldSingleMonthTaskRetainsItsFailureScope() {
        var row=job();row.setScanMode("FULL");var service=service(row);
        when(commits.selectDiffFailures(100,"SELF",1,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"))).thenReturn(List.of());
        try {
            assertEquals("PARTIAL",service.scanState(scope,CodeRange.parse("2026-11-01","2026-11-04",24,clock)).aggregateStatus());
            assertEquals("2026-11-01",service.failures(scope,41,1,20).from());
            verify(commits).selectDiffFailures(100,"SELF",1,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"));
        } finally {service.shutdown();}
    }
}
