package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeScanWorkerPartialTest {
    @Test
    void failedBranchDoesNotSkipRemainingBranchesOrOtherRepositories() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        var jobs = mock(ZhaogangCodeScanJobMapper.class);
        var repos = mock(ZhaogangCodeScanRepoMapper.class);
        var commits = mock(ZhaogangCodeCommitMapper.class);
        var cache = mock(CodeStatsViewCache.class);
        var identities = mock(CodeIdentityResolver.class);
        var job = new ScanJobEntity();
        job.setId(1L); job.setCodingTeamId(100L); job.setScopeType("SELF"); job.setScopeId(1L);
        job.setMonth("2026-10"); job.setTotalRepos(0); job.setDoneRepos(0);
        job.setTotalBranches(0); job.setDoneBranches(0); job.setTotalCommits(0); job.setDoneCommits(0);
        job.setTotalDiffs(0); job.setDoneDiffs(0); job.setFailedRepos(0); job.setFailedDiffs(0); job.setTruncated(false);
        when(jobs.selectById(1L)).thenReturn(job);
        when(jobs.updateProgress(any())).thenReturn(1);
        when(identities.resolve(anyMap(), nullable(String.class), nullable(String.class)))
                .thenReturn(new CodeIdentityResolver.Resolved(1L, false));
        when(coding.myDepots(anyString(), anyInt(), anyInt())).thenReturn(new CodingDepotPage(List.of(
                new CodingDepot(1, 10, "p", "p", "one", "", "git", "CODING"),
                new CodingDepot(2, 10, "p", "p", "two", "", "git", "CODING")), 2, 1, 100));
        when(coding.branchHeads(anyString(), anyLong(), eq(1L), anyString()))
                .thenReturn(List.of(new CodingBranchHead("broken", "", null), new CodingBranchHead("good", "", null)));
        when(coding.branchHeads(anyString(), anyLong(), eq(2L), anyString()))
                .thenReturn(List.of(new CodingBranchHead("good", "", null)));
        when(coding.commits(anyString(), any())).thenAnswer(invocation -> {
            CodingCommitQuery query = invocation.getArgument(1);
            if (query.ref().equals("broken")) throw new CodingOpenApiException("REQUEST_FAILED", "fixture failure");
            return new CodingCommitPage(List.of(new CodingCommit("sha-" + query.depotId(), "sha", "author",
                    "author@example.com", Instant.parse("2026-10-02T02:00:00Z").toEpochMilli(), "sample", List.of())), 1, 1, 100);
        });
        var scope = new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF, 1, 100, "team", "https://example.com", "self",
                List.of(new CodeStatsModule.MemberCredential(1, "self", "", "fixture-token")), false);
        new CodeScanWorker(coding, jobs, repos, commits, identities, cache, new ZhaogangProperties(),
                Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai")))
                .run(new CodeScanWorker.ScanInput(job, scope, YearMonth.of(2026, 10), "FULL", 1));
        assertEquals("PARTIAL", job.getStatus());
        assertEquals(2, job.getTotalCommits());
        assertEquals(1, job.getFailedRepos());
        verify(commits, times(2)).upsertBatch(argThat(batch -> batch.size() == 1));
        verify(commits, never()).cleanupFullScan(anyLong(), anyString(), anyLong(), anyString(), anyLong());
    }
}
