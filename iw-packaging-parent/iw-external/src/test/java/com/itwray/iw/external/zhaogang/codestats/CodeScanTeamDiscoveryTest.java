package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeScanTeamDiscoveryTest {
    @Test
    void discoversMembersConcurrentlyButReadsSharedRepositoryOnlyOnce() {
        run(false);
    }
    @Test
    void oneMemberPermissionFailureRetainsSuccessfulMemberAndMarksPartial() {
        run(true);
    }
    private void run(boolean failure) {
        var coding=mock(CodingOpenApiPort.class);var jobs=mock(ZhaogangCodeScanJobMapper.class);
        var repos=mock(ZhaogangCodeScanRepoMapper.class);var commits=mock(ZhaogangCodeCommitMapper.class);
        var cache=mock(CodeStatsViewCache.class);var identities=mock(CodeIdentityResolver.class);
        var bothStarted=new CountDownLatch(2);
        var job=new ScanJobEntity();job.setId(1L);job.setCodingTeamId(100L);job.setScopeType("WORKBENCH_TEAM");
        job.setScopeId(9L);job.setMonth("2026-10");job.setTotalRepos(0);job.setDoneRepos(0);
        job.setTotalBranches(0);job.setDoneBranches(0);job.setTotalCommits(0);job.setDoneCommits(0);
        job.setTotalDiffs(0);job.setDoneDiffs(0);job.setFailedRepos(0);job.setFailedDiffs(0);job.setTruncated(false);
        when(jobs.updateProgress(any())).thenReturn(1);when(jobs.selectById(1L)).thenReturn(job);
        when(coding.myDepots(anyString(),anyInt(),anyInt())).thenAnswer(call->{
            bothStarted.countDown();assertTrue(bothStarted.await(2,TimeUnit.SECONDS),"members must start concurrently");
            if(failure && "fixture-two".equals(call.getArgument(0))) throw new CodingOpenApiException("PERMISSION_DENIED","fixture");
            return new CodingDepotPage(List.of(new CodingDepot(1,10,"p","p","shared","","git","CODING")),1,1,100);
        });
        when(coding.branchHeads(anyString(),anyLong(),anyLong(),anyString()))
                .thenReturn(List.of(new CodingBranchHead("main","a".repeat(40),null)));
        when(coding.commits(anyString(),any())).thenReturn(new CodingCommitPage(List.of(
                new CodingCommit("sha","sha","one","one@example.com",Instant.parse("2026-10-02T02:00:00Z").toEpochMilli(),"sample",List.of())),1,1,100));
        when(identities.resolve(anyMap(),nullable(String.class),nullable(String.class)))
                .thenReturn(new CodeIdentityResolver.Resolved(1L,false));
        var scope=new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.WORKBENCH_TEAM,9,100,"team","https://example.com","team",
                List.of(new CodeStatsModule.MemberCredential(1,"one","","fixture-one"),new CodeStatsModule.MemberCredential(2,"two","","fixture-two")),true);
        new CodeScanWorker(coding,jobs,repos,commits,identities,cache,new ZhaogangProperties(),
                Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"),CodeStatsSupport.CHINA_ZONE))
                .run(new CodeScanWorker.ScanInput(job,scope,YearMonth.of(2026,10),"FULL",1));
        assertEquals(failure?"PARTIAL":"COMPLETED",job.getStatus());
        assertEquals(1,job.getTotalRepos());assertEquals(1,job.getTotalCommits());
        verify(coding,times(1)).commits(anyString(),any());
        verify(commits,times(1)).upsertBatch(argThat(rows -> rows.size()==1));
    }
}
