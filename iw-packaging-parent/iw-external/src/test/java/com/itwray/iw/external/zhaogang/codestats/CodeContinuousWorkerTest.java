package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeContinuousWorkerTest {
    final CodingOpenApiPort coding=mock(CodingOpenApiPort.class);
    final ZhaogangCodeScanJobMapper jobs=mock(ZhaogangCodeScanJobMapper.class);
    final ZhaogangCodeScanRepoMapper repos=mock(ZhaogangCodeScanRepoMapper.class);
    final ZhaogangCodeCommitMapper commits=mock(ZhaogangCodeCommitMapper.class);
    final CodeIdentityResolver identities=mock(CodeIdentityResolver.class);
    final CodeStatsViewCache cache=mock(CodeStatsViewCache.class);
    final Map<String,CommitEntity> database=new HashMap<>();
    final long at=Instant.parse("2026-10-10T02:00:00Z").toEpochMilli();
    final String a="a".repeat(40), b="b".repeat(40);
    String head=a, checkpoint;
    ScanJobEntity job;
    final CodeStatsModule.ScopeInfo scope=new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF,1,100,"team","https://example.com","self",
            List.of(new CodeStatsModule.MemberCredential(1,"self","","fixture-token")),false);

    CodeContinuousWorkerTest() {
        when(jobs.selectById(anyLong())).thenAnswer(v->job);
        when(jobs.updateProgress(any())).thenReturn(1);
        when(identities.signature(anyList())).thenReturn("selection");
        when(identities.signature(any(CodeStatsModule.ScopeInfo.class))).thenReturn("selection");
        when(identities.resolve(anyMap(),nullable(String.class),nullable(String.class))).thenReturn(new CodeIdentityResolver.Resolved(1L,false));
        when(coding.myDepots(anyString(),anyInt(),anyInt())).thenReturn(new CodingDepotPage(List.of(new CodingDepot(1,10,"p","p","repo","","git","CODING")),1,1,100));
        when(coding.branchHeads(anyString(),anyLong(),anyLong(),anyString())).thenAnswer(v->List.of(
                new CodingBranchHead("main",head,head.equals(b)?Instant.parse("2026-11-03T01:00:00Z").toEpochMilli():at),
                new CodingBranchHead("release",head,head.equals(b)?Instant.parse("2026-11-03T01:00:00Z").toEpochMilli():at)));
        when(coding.commits(anyString(),any())).thenAnswer(v->{
            CodingCommitQuery q=v.getArgument(1);
            assertEquals("2026-01-01",q.startDate());
            return new CodingCommitPage(List.of(new CodingCommit(head,"sha","self","self@example.invalid",at,"fixture",head.equals(a)?List.of():List.of(a))),1,1,100);
        });
        when(commits.selectSyncRepo(anyLong(),anyString(),anyLong(),anyLong(),any(),any())).thenAnswer(v->new ArrayList<>(database.values()));
        when(commits.upsertBatch(anyList())).thenAnswer(v->{
            List<CommitEntity> rows=v.getArgument(0); rows.forEach(row->database.put(row.getSha(),row)); return rows.size();
        });
        when(commits.cleanupSyncRepo(anyLong(),anyString(),anyLong(),anyLong(),any(),any(),anyLong())).thenAnswer(v->{
            database.values().removeIf(row->!row.getLastSeenScanId().equals(job.getId()));return 1;
        });
        when(repos.selectSyncCheckpoint(anyLong(),anyString(),anyLong(),anyLong(),anyString(),any(),anyLong())).thenAnswer(v->{
            if(checkpoint==null)return null;var result=new ScanRepoEntity();result.setSyncState(checkpoint);return result;
        });
        when(repos.saveSyncCheckpoint(anyLong(),anyLong(),anyString())).thenAnswer(v->{checkpoint=v.getArgument(2);return 1;});
    }
    ScanJobEntity run(long id,String today) {
        Clock clock=Clock.fixed(LocalDate.parse(today).atStartOfDay(CodeStatsSupport.CHINA_ZONE).toInstant(),CodeStatsSupport.CHINA_ZONE);
        job=new ScanJobEntity();job.setId(id);job.setCodingTeamId(100L);job.setScopeType("SELF");job.setScopeId(1L);
        job.setMonth(YearMonth.now(clock).toString());job.setScanMode("SYNC");job.setSyncFrom(LocalDate.parse("2026-01-01"));job.setSyncTo(LocalDate.parse(today));
        job.setTotalRepos(0);job.setDoneRepos(0);job.setTotalBranches(0);job.setDoneBranches(0);job.setTotalCommits(0);job.setDoneCommits(0);
        job.setTotalDiffs(0);job.setDoneDiffs(0);job.setFailedRepos(0);job.setFailedDiffs(0);job.setTruncated(false);
        new CodeScanWorker(coding,jobs,repos,commits,identities,cache,new ZhaogangProperties(),clock)
                .run(new CodeScanWorker.ScanInput(job,scope,YearMonth.now(clock),"SYNC",1));return job;
    }
    @Test void sameHeadIsCoalescedAndRestartedCrossDaySyncReadsZeroCommitPages() throws Exception {
        assertEquals("COMPLETED",run(1,"2026-11-02").getStatus());
        assertNotNull(checkpoint);assertTrue(checkpoint.startsWith("gz1:"));
        assertEquals(2,database.get(a).getBranchCount());
        assertEquals("COMPLETED",run(2,"2026-11-03").getStatus());
        verify(coding,times(1)).commits(anyString(),any());
        assertEquals(1,database.size());assertEquals(2L,database.get(a).getLastSeenScanId());
        verify(commits,times(22)).cleanupFullScan(anyLong(),anyString(),anyLong(),anyString(),anyLong());
        assertEquals(Set.of(a),CodeSyncCheckpointCodec.decode(checkpoint).heads().get(a).matched());
    }
    @Test void changedHeadOnlyAddsNewCommitAndPreservesKnownHistory() {
        run(1,"2026-11-02");head=b;
        assertEquals("COMPLETED",run(2,"2026-11-03").getStatus());
        verify(coding,times(2)).commits(anyString(),any());
        assertEquals(Set.of(a,b),database.keySet());
        assertTrue(database.values().stream().allMatch(v->v.getBranchCount()==2));
    }
    @Test void failedNewReadCannotAdvanceCheckpointOrDeleteExistingData() {
        run(1,"2026-11-02");String before=checkpoint;head=b;
        doThrow(new CodingOpenApiException("REQUEST_FAILED","fixture")).when(coding).commits(anyString(),any());
        assertEquals("PARTIAL",run(2,"2026-11-03").getStatus());
        try {
            var decoded=CodeSyncCheckpointCodec.decode(checkpoint);
            assertFalse(decoded.branches().containsValue(b));assertFalse(decoded.graph().containsKey(b));
            assertTrue(decoded.graph().containsKey(a));
        } catch(Exception e) { throw new AssertionError(e); }
        assertEquals(Set.of(a),database.keySet());
        verify(commits,times(1)).cleanupSyncRepo(anyLong(),anyString(),anyLong(),anyLong(),any(),any(),anyLong());
    }
    @Test void corruptCheckpointFallsBackToRemoteInsteadOfFalseZero() {
        checkpoint="not-json";
        assertEquals("COMPLETED",run(1,"2026-11-03").getStatus());
        verify(coding,times(1)).commits(anyString(),any());assertEquals(Set.of(a),database.keySet());
    }
    @Test void movingOneRefStillPublishesOtherStableRefsAndNextSyncOnlyReadsGap() throws Exception {
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        when(coding.branchHeads(anyString(),anyLong(),anyLong(),anyString())).thenAnswer(v->{
            if(reads.incrementAndGet()>1) head=b;
            return List.of(new CodingBranchHead("main",head,at),new CodingBranchHead("release",a,at));
        });
        doAnswer(v->{
            CodingCommitQuery q=v.getArgument(1);
            List<CodingCommit> rows=new ArrayList<>();
            if(q.ref().equals(b) && head.equals(b)) rows.add(new CodingCommit(b,"b","self","self@example.invalid",at,"new",List.of(a)));
            rows.add(new CodingCommit(a,"a","self","self@example.invalid",at,"old",List.of()));
            return new CodingCommitPage(rows,rows.size(),1,100);
        }).when(coding).commits(anyString(),any());
        assertEquals("PARTIAL",run(1,"2026-11-02").getStatus());
        assertEquals(Map.of("release",a),CodeSyncCheckpointCodec.decode(checkpoint).branches());
        verify(commits,never()).cleanupSyncRepo(anyLong(),anyString(),anyLong(),anyLong(),any(),any(),anyLong());
        assertEquals("COMPLETED",run(2,"2026-11-03").getStatus());
        verify(coding,times(2)).commits(anyString(),any());
        assertEquals(Set.of(a,b),database.keySet());
    }

    @Test void upstreamFileCountLimitIsClassifiedAsUnavailableLinesNotRepeatedTransportFailure() {
        var pending=new CommitEntity();pending.setId(77L);pending.setDepotId(1L);pending.setProjectId(10L);pending.setSha(a);pending.setDepotName("repo");
        when(commits.selectPendingDiffs(anyLong(),anyString(),anyLong(),eq("2026-10"))).thenReturn(List.of(pending));
        doThrow(new CodingOpenApiException("FailedOperation","diff 修改文件过多，不宜在网页展示"))
                .when(coding).commitDiff(anyString(),anyLong(),anyLong(),anyString());
        assertEquals("PARTIAL",run(1,"2026-11-03").getStatus());
        verify(commits).updateDiffStatus(77L,"TOO_LARGE");
        // 行数不可用不影响已完成元数据的可见范围清理，不能永久保留已不可达提交。
        verify(commits,times(11)).cleanupFullScan(anyLong(),anyString(),anyLong(),anyString(),anyLong());
        verify(commits,never()).updateDiff(77L,0L,0L,0,"OK");
    }

}
