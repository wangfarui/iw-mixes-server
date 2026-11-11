package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeScanWorkerBranchCacheTest {
    final CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
    final ZhaogangCodeScanJobMapper jobs = mock(ZhaogangCodeScanJobMapper.class);
    final ZhaogangCodeScanRepoMapper repos = mock(ZhaogangCodeScanRepoMapper.class);
    final ZhaogangCodeCommitMapper commits = mock(ZhaogangCodeCommitMapper.class);
    final CodeStatsViewCache cache = mock(CodeStatsViewCache.class);
    final CodeIdentityResolver identities = mock(CodeIdentityResolver.class);
    final Map<String, CodeScanWorker.BranchSnapshot> snapshots = new ConcurrentHashMap<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    final CodeStatsModule.ScopeInfo scope = new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF,
            1, 100, "team", "https://example.com", "self",
            List.of(new CodeStatsModule.MemberCredential(1, "self", "", "fixture-token")), false);
    ScanJobEntity current;
    List<CodingBranchHead> heads = heads("a");
    List<CommitEntity> saved = new ArrayList<>();

    CodeScanWorkerBranchCacheTest() {
        when(jobs.selectById(anyLong())).thenAnswer(invocation -> current);
        when(jobs.updateProgress(any())).thenReturn(1);
        when(identities.resolve(anyMap(), nullable(String.class), nullable(String.class)))
                .thenReturn(new CodeIdentityResolver.Resolved(1L, false));
        when(coding.myDepots(anyString(), anyInt(), anyInt())).thenReturn(new CodingDepotPage(List.of(
                new CodingDepot(1, 10, "p", "p", "repo", "", "git", "CODING")), 1, 1, 100));
        when(coding.branchHeads(anyString(), anyLong(), anyLong(), anyString())).thenAnswer(invocation -> heads);
        when(cache.scopeKey(any())).thenAnswer(invocation -> {
            CodeStatsModule.ScopeInfo info = invocation.getArgument(0);
            return info.codingTeamId() + ":" + info.scope() + ":" + info.scopeId();
        });
        when(cache.get(anyString(), eq(CodeScanWorker.BranchSnapshot.class)))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.getArgument(0))));
        doAnswer(invocation -> { snapshots.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(cache).putBranchSnapshot(anyString(), any());
        when(commits.upsertBatch(anyList())).thenAnswer(invocation -> {
            saved.addAll(invocation.getArgument(0)); return 1;
        });
        when(coding.commits(anyString(), any())).thenAnswer(invocation -> page(invocation.getArgument(1)));
    }

    private List<CodingBranchHead> heads(String main) {
        return List.of(new CodingBranchHead("main", main.repeat(40), null),
                new CodingBranchHead("release", main.repeat(40), null),
                new CodingBranchHead("feature", "b".repeat(40), null));
    }

    private CodingCommitPage page(CodingCommitQuery query) {
        long date = LocalDate.parse(query.startDate()).plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
        return new CodingCommitPage(List.of(new CodingCommit(query.ref(), "sha", "author", "author@example.com",
                date, "sample", List.of())), 1, query.pageNumber(), query.pageSize());
    }

    private ScanJobEntity run(int id, String month) {
        current = new ScanJobEntity(); current.setId((long) id); current.setCodingTeamId(100L);
        current.setScopeType("SELF"); current.setScopeId(1L); current.setMonth(month);
        current.setTotalRepos(0); current.setDoneRepos(0); current.setTotalBranches(0); current.setDoneBranches(0);
        current.setTotalCommits(0); current.setDoneCommits(0); current.setTotalDiffs(0); current.setDoneDiffs(0);
        current.setFailedRepos(0); current.setFailedDiffs(0); current.setTruncated(false);
        saved.clear();
        new CodeScanWorker(coding, jobs, repos, commits, identities, cache, new ZhaogangProperties(), clock)
                .run(new CodeScanWorker.ScanInput(current, scope, YearMonth.parse(month), "FULL", 1));
        return current;
    }

    @Test
    void scannedCommitsUseTheExistingGitRouteInTheRepositoryWebUrl() {
        String repositoryUrl = "https://team.coding.net/p/project/d/repo/git";
        when(coding.myDepots(anyString(), anyInt(), anyInt())).thenReturn(new CodingDepotPage(List.of(
                new CodingDepot(1, 10, "project", "Project", "repo", repositoryUrl + "/", "git", "CODING")),
                1, 1, 100));

        assertEquals("COMPLETED", run(1, "2026-10").getStatus());
        assertEquals(2, saved.size());
        for (CommitEntity commit : saved) {
            assertEquals(repositoryUrl + "/commit/" + commit.getSha(), commit.getCommitUrl());
        }
    }

    @Test
    void coldScanCoalescesSameHeadAndWarmScanReadsNoCommitPages() {
        assertEquals("COMPLETED", run(1, "2026-10").getStatus());
        verify(coding, times(2)).commits(anyString(), any());
        assertEquals(2, snapshots.size());
        assertTrue(saved.stream().anyMatch(row -> row.getBranchCount() == 2));
        assertEquals("COMPLETED", run(2, "2026-10").getStatus());
        verify(coding, times(2)).commits(anyString(), any());
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(row -> row.getLastSeenScanId() == 2L));
    }

    @Test
    void changedHeadAndDifferentMonthAreNotReused() {
        run(1, "2026-10");
        heads = heads("c");
        run(2, "2026-10");
        verify(coding, times(3)).commits(anyString(), any());
        run(3, "2026-09");
        verify(coding, times(5)).commits(anyString(), any());
    }

    @Test
    void missingShaNeverAllowsAnEmptyOrStaleCacheHit() {
        heads = List.of(new CodingBranchHead("main", "", null));
        run(1, "2026-10"); run(2, "2026-10");
        verify(coding, times(2)).commits(anyString(), any());
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void headMovedDuringReadDoesNotPublishSnapshotOrCleanExistingData() {
        doAnswer(invocation -> {
            heads = heads("d"); return page(invocation.getArgument(1));
        }).when(coding).commits(anyString(), any());
        assertEquals("PARTIAL", run(1, "2026-10").getStatus());
        assertFalse(snapshots.keySet().stream().anyMatch(key -> key.endsWith("a".repeat(40))));
        verify(commits, never()).cleanupFullScan(anyLong(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    void failedReadIsNotPublishedAsACompleteCacheEntry() {
        doThrow(new CodingOpenApiException("REQUEST_FAILED", "fixture failure"))
                .when(coding).commits(anyString(), any());
        assertEquals("PARTIAL", run(1, "2026-10").getStatus());
        assertTrue(snapshots.isEmpty());
        run(2, "2026-10");
        verify(coding, atLeast(4)).commits(anyString(), any());
    }
    @Test
    void newAliasReusesHeadButRebuildsMembershipAndOtherRepositoryCannotReuseIt() {
        run(1, "2026-10");
        heads = List.of(new CodingBranchHead("renamed", "a".repeat(40), null));
        run(2, "2026-10");
        verify(coding, times(2)).commits(anyString(), any());
        assertEquals(1, saved.size());
        assertEquals("[\"renamed\"]", saved.get(0).getBranchNames());
        when(coding.myDepots(anyString(), anyInt(), anyInt())).thenReturn(new CodingDepotPage(List.of(
                new CodingDepot(2, 10, "p", "p", "other", "", "git", "CODING")), 1, 1, 100));
        run(3, "2026-10");
        verify(coding, times(3)).commits(anyString(), any());
    }

    @Test
    void failedSecondPageKeepsFirstPageButCannotBeCached() {
        heads = List.of(new CodingBranchHead("main", "a".repeat(40), null));
        doAnswer(invocation -> {
            CodingCommitQuery query = invocation.getArgument(1);
            if (query.pageNumber() > 1) throw new CodingOpenApiException("REQUEST_FAILED", "fixture second page");
            CodingCommit row = page(query).items().get(0);
            return new CodingCommitPage(java.util.Collections.nCopies(100, row), 101, 1, 100);
        }).when(coding).commits(anyString(), any());
        assertEquals("PARTIAL", run(1, "2026-10").getStatus());
        assertEquals(1, saved.size());
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void corruptCachedSnapshotFallsBackToTheApi() {
        run(1, "2026-10");
        snapshots.replaceAll((key, value) -> new CodeScanWorker.BranchSnapshot(List.of(new CommitEntity())));
        assertEquals("COMPLETED", run(2, "2026-10").getStatus());
        verify(coding, times(4)).commits(anyString(), any());
    }

    @Test
    void truncatedBranchSampleDoesNotDependOnWhichHeadsWereCached() {
        heads = java.util.stream.IntStream.rangeClosed(1, 61)
                .mapToObj(i -> new CodingBranchHead(String.format("branch%02d", i), String.format("%040x", i), null)).toList();
        doAnswer(invocation -> {
            CodingCommitQuery query = invocation.getArgument(1);
            CodingCommit row = page(query).items().get(0);
            return new CodingCommitPage(List.of(new CodingCommit("shared", "shared", row.authorName(), row.authorEmail(),
                    row.commitAtMillis(), "same commit", List.of())), 1, 1, 100);
        }).when(coding).commits(anyString(), any());
        run(1, "2026-10");
        assertEquals(1, saved.size());
        String sample = saved.get(0).getBranchNames();
        snapshots.keySet().removeIf(key -> key.endsWith(String.format("%040x", 1)));
        run(2, "2026-10");
        assertEquals(61, saved.get(0).getBranchCount());
        assertEquals(sample, saved.get(0).getBranchNames());
    }

    @Test
    void apijarSkipsDiffOnFreshReadsAndCachedReplay() {
        when(coding.myDepots(anyString(), anyInt(), anyInt())).thenReturn(new CodingDepotPage(List.of(
                new CodingDepot(1, 10, "p", "p", "online.base.apijar.service", "", "git", "CODING")), 1, 1, 100));
        CommitEntity legacy = new CommitEntity();
        legacy.setId(77L); legacy.setDepotId(1L); legacy.setProjectId(10L); legacy.setSha("legacy");
        legacy.setDepotName("online.base.apijar.service");
        legacy.setDiffStatus("FAILED"); legacy.setIsMerge(false);
        when(commits.selectPendingDiffs(anyLong(), anyString(), anyLong(), anyString())).thenReturn(List.of(legacy));
        when(identities.resolve(anyMap(), nullable(String.class), nullable(String.class)))
                .thenReturn(new CodeIdentityResolver.Resolved(1L, false));
        when(coding.commitDiff(anyString(), anyLong(), anyLong(), nullable(String.class)))
                .thenReturn(new CodingCommitDiff(20, 10, 1));
        assertEquals("COMPLETED", run(1, "2026-10").getStatus());
        assertTrue(saved.stream().allMatch(row -> "SKIPPED_REPOSITORY".equals(row.getDiffStatus())
                && row.getAdditions() == null && row.getDeletions() == null && row.getFilesChanged() == null));
        assertEquals("COMPLETED", run(2, "2026-10").getStatus());
        assertTrue(saved.stream().allMatch(row -> "SKIPPED_REPOSITORY".equals(row.getDiffStatus())));
        verify(coding, never()).commitDiff(anyString(), anyLong(), anyLong(), nullable(String.class));
        verify(commits, atLeastOnce()).updateDiff(77L, null, null, null, "SKIPPED_REPOSITORY");
    }

    @Test
    void dropsUnmatchedMetadataBeforeDatabaseAndSnapshotWrites() {
        when(identities.resolve(anyMap(), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> "owned".equals(call.getArgument(1))
                        ? new CodeIdentityResolver.Resolved(1L, false)
                        : CodeIdentityResolver.Resolved.UNMAPPED);
        doAnswer(call -> {
            CodingCommitQuery q = call.getArgument(1);
            var sample = page(q).items().get(0);
            return new CodingCommitPage(List.of(
                    new CodingCommit("other", "other", "other", "other@example.com", sample.commitAtMillis(), "other", List.of()),
                    new CodingCommit("mine", "mine", "author", "mine@example.com", sample.commitAtMillis(), "mine", List.of())), 2, 1, 100);
        }).when(coding).commits(anyString(), any());
        when(identities.resolve(anyMap(), eq(CodeStatsSupport.emailHash("mine@example.com")), nullable(String.class)))
                .thenReturn(new CodeIdentityResolver.Resolved(1L, false));
        run(1, "2026-10");
        assertEquals(1, saved.size());
        assertEquals("mine", saved.get(0).getSha());
        assertTrue(snapshots.values().stream().flatMap(v -> v.commits().stream())
                .allMatch(row -> "mine".equals(row.getSha())));
    }

    @Test
    void identityChangeInvalidatesEvenUnchangedHeads() {
        when(identities.signature(anyList())).thenReturn("first");
        when(identities.signature(any(CodeStatsModule.ScopeInfo.class))).thenReturn("first");
        run(1, "2026-10");
        when(identities.signature(anyList())).thenReturn("second");
        when(identities.signature(any(CodeStatsModule.ScopeInfo.class))).thenReturn("second");
        run(2, "2026-10");
        verify(coding, times(4)).commits(anyString(), any());
        assertTrue(snapshots.keySet().stream().allMatch(key -> key.startsWith("branch-month:v2:")));
    }

    @Test
    void identityEditedDuringReadStopsBeforePublishingStaleRowsOrCache() {
        when(identities.signature(anyList())).thenReturn("before");
        when(identities.signature(any(CodeStatsModule.ScopeInfo.class))).thenReturn("after");
        assertEquals("FAILED", run(1, "2026-10").getStatus());
        assertEquals("IDENTITY_CHANGED", current.getErrorCode());
        assertTrue(saved.isEmpty());
        assertTrue(snapshots.isEmpty());
    }

}
