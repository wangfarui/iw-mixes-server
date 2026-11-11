package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Coverage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.EntryPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberMonthly;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanMonth;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanProgress;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanState;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SelfStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.TeamStatistics;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeStatsQueryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private ZhaogangCodeCommitMapper commitMapper;
    private ZhaogangCodeScanJobMapper jobMapper;
    private ZhaogangCodeScanRepoMapper repoMapper;
    private ZhaogangCodeIdentityMapper identityMapper;
    private CodeStatsQueryService service;
    private CodeRange range;

    @BeforeEach
    void setUp() {
        commitMapper = mock(ZhaogangCodeCommitMapper.class);
        jobMapper = mock(ZhaogangCodeScanJobMapper.class);
        repoMapper = mock(ZhaogangCodeScanRepoMapper.class);
        identityMapper = mock(ZhaogangCodeIdentityMapper.class);
        when(identityMapper.selectByScope(anyLong(), anyString(), anyLong())).thenReturn(List.of());
        when(jobMapper.selectLatest(anyLong(), anyString(), anyLong(), anyString())).thenReturn(null);
        when(jobMapper.selectLatestSuccessful(anyLong(), anyString(), anyLong(), anyString())).thenReturn(null);
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(new ArrayList<>());
        WorkCalendarResolver calendar = (codingTeamId, month) -> new Schedule(month.toString(), 1,
                month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                        .map(date -> new Day(date, date.equals(LocalDate.of(2026, 10, 5))
                                ? com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType.REST_DAY
                                : WorkCalendarDefaults.dayType(date), true, false))
                        .toList());
        service = new CodeStatsQueryService(commitMapper, jobMapper, repoMapper,
                new CodeIdentityResolver(identityMapper), calendar);
        range = CodeRange.parse("2026-10-01", "2026-10-30", 24, clock);
    }

    @Test
    void teamStatisticsIncludesOnlyDevelopersAndKeepsZeroCommitDeveloperLast() {
        CodeStatsModule.ScopeInfo scope = teamScope();
        IdentityEntity email1 = mapping("EMAIL", "e1", 1L, false);
        IdentityEntity email2 = mapping("EMAIL", "e2", 2L, false);
        IdentityEntity ignored = mapping("EMAIL", "e3", null, true);
        when(identityMapper.selectByScope(100L, "SELF", 1L))
                .thenReturn(List.of(email1, ignored, mapping("NAME", "n1", 1L, false)));
        when(identityMapper.selectByScope(100L, "SELF", 2L)).thenReturn(List.of(email2));
        when(identityMapper.selectByScope(100L, "SELF", 4L)).thenReturn(List.of(mapping("EMAIL", "qa", 4L, false)));
        when(identityMapper.selectByScope(100L, "SELF", 5L)).thenReturn(List.of(mapping("EMAIL", "pm", 5L, false)));
        when(identityMapper.selectByScope(100L, "SELF", 6L)).thenReturn(List.of(mapping("EMAIL", "unset", 6L, false)));
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(
                        commit("s1", "e1", "n1", "2026-10-02T10:00:00", "OK", 100, 20, 2, "main"),
                        commit("s2", "e1", "n1", "2026-10-05T11:00:00", "FAILED", null, null, null, "main"),
                        commit("s3", "e2", "n2", "2026-10-06T12:00:00", "OK", 50, 10, 1, "feature/a"),
                        commit("s4", "e9", "n9", "2026-10-07T12:00:00", "SKIPPED_UNMAPPED", null, null, null, "main"),
                        commit("s5", "e3", "n3", "2026-10-08T12:00:00", "SKIPPED_UNMAPPED", null, null, null, "main"),
                        commit("qa", "qa", "n1", "2026-10-09T12:00:00", "OK", 900, 200, 2, "main"),
                        commit("pm", "pm", "pm", "2026-10-09T13:00:00", "OK", 700, 100, 2, "main"),
                        commit("unset", "unset", "unset", "2026-10-09T14:00:00", "OK", 800, 300, 2, "main")));

        TeamStatistics statistics = service.teamStatistics(scope, range, completedScan(), false, true);

        assertEquals(3, statistics.members().size());
        MemberMonthly first = statistics.members().get(0);
        MemberMonthly second = statistics.members().get(1);
        MemberMonthly third = statistics.members().get(2);
        assertEquals(1L, first.user().id());
        assertEquals(2, first.commitCount());
        assertEquals(100, first.additions());
        assertEquals(20, first.deletions());
        assertEquals(2L, second.user().id());
        assertEquals(1, second.commitCount());
        assertEquals(50, second.additions());
        assertEquals(10, second.deletions());
        assertEquals(3L, third.user().id());
        assertEquals(0, third.commitCount());
        assertEquals(0, statistics.coverage().excludedMergeCommitCount());
        assertEquals(1, statistics.coverage().memberIssues().size());
        assertEquals(1, statistics.coverage().failedDiffCount());
        assertTrue(statistics.coverage().memberIssues().get(0).reasonCode().equals("TOKEN_MISSING"));
        assertEquals(0.667D, statistics.coverage().diffCoverage());
        assertEquals(1, service.memberStatistics(scope, range, 2L).summary().commitCount());
        for (long excludedId : List.of(4L, 5L, 6L)) {
            assertThrows(CodingOpenApiException.class, () -> service.memberStatistics(scope, range, excludedId));
        }
    }

    @Test
    void selfStatisticsFillsEveryDayAndUsesWorkCalendarDayType() {
        CodeStatsModule.ScopeInfo scope = selfScope();
        when(identityMapper.selectByScope(100L, "SELF", 1L))
                .thenReturn(List.of(mapping("EMAIL", "e1", 1L, false)));
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(
                        commit("s1", "e1", "n1", "2026-10-02T10:00:00", "OK", 100, 20, 2, "main"),
                        commit("s2", "e1", "n1", "2026-10-05T11:00:00", "OK", 20, 5, 1, "feature/a"),
                        commit("s3", "e9", "n9", "2026-10-06T12:00:00", "SKIPPED_UNMAPPED", null, null, null, "main")));

        SelfStatistics statistics = service.selfStatistics(scope, range, completedScan(), false, true);

        assertEquals(30, statistics.daily().size());
        assertEquals("REST_DAY", statistics.daily().get(4).dayType());
        assertEquals(2, statistics.summary().commitCount());
        assertEquals(2, statistics.summary().activeDays());
        assertEquals(0, statistics.coverage().unmatchedCommitCount());
        assertEquals(2, statistics.repositories().size());
        assertEquals(2, statistics.branches().size());
        assertEquals(1, statistics.hourly().get(11).commitCount());
        assertEquals(2, statistics.summary().repoCount());
    }

    @Test
    void selfEntriesFilterByDepotKeywordAndPaginate() {
        CodeStatsModule.ScopeInfo scope = selfScope();
        when(identityMapper.selectByScope(100L, "SELF", 1L))
                .thenReturn(List.of(mapping("EMAIL", "e1", 1L, false)));
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(
                        withMessage(commit("s1", "e1", "n1", "2026-10-02T10:00:00", "OK", 100, 20, 2, "main"),
                                "修复库存查询"),
                        withMessage(commit("s2", "e1", "n1", "2026-10-03T10:00:00", "OK", 10, 2, 1, "main"),
                                "订单状态"),
                        withMessage(commit("s3", "e9", "n9", "2026-10-04T10:00:00", "OK", 1, 1, 1, "main"),
                                "其他作者")));

        EntryPage page = service.entries(scope, range, null, "库存", 1, 1);

        assertEquals(1, page.page().totalRow());
        assertEquals(1, page.items().size());
        assertEquals("s1", page.items().get(0).sha());
        assertEquals(100L, page.items().get(0).additions());
        assertFalse(page.items().get(0).branchTruncated());
        assertNull(service.entries(scope, range, 999L, null, 1, 20).items().stream().findFirst().orElse(null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RUNNING", "PARTIAL", "INTERRUPTED", "FAILED"})
    void partialRowsRemainVisibleWithoutACompletedMonth(String status) {
        when(commitMapper.countMerges(anyLong(), anyString(), anyLong(), any(), any())).thenReturn(0L);
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(commit("partial", "e1", "n1", "2026-10-02T10:00:00", "PENDING", null, null, null, "main")));
        when(identityMapper.selectByScope(anyLong(), anyString(), anyLong()))
                .thenReturn(List.of(mapping("EMAIL", "e1", 1L, false)));
        var job = new com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity();
        job.setId(9L); job.setTotalRepos(2);
        when(jobMapper.selectLatest(anyLong(), anyString(), anyLong(), anyString())).thenReturn(job);
        var good = new com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity();
        good.setDepotId(1L); good.setBranchCount(3); good.setStatus("COMPLETED");
        var failed = new com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity();
        failed.setDepotId(2L); failed.setStatus("FAILED"); failed.setErrorCode("REQUEST_FAILED");
        when(repoMapper.selectByJob(9L)).thenReturn(List.of(good, failed));
        when(repoMapper.selectFailures(9L)).thenReturn(List.of(failed));
        ScanMonth month = new ScanMonth("2026-10", 9L, "FULL", status, "COMMITS",
                new ScanProgress(2, 2, 3, 3, 1, 1, 0, 0), null, null, null, null, 0, null);
        ScanState scan = new ScanState("SELF", null, range.view(), status, List.of(month), 0);
        SelfStatistics mine = service.selfStatistics(selfScope(), range, scan, false, false);
        assertTrue(mine.dataReady());
        assertEquals(1, mine.summary().commitCount());
        assertTrue(mine.coverage().partial());
        assertEquals(1, mine.coverage().repoIssues().size());
        assertEquals(1, mine.coverage().repoCount());
        assertEquals(3, mine.coverage().branchCount());
        assertEquals(0D, mine.coverage().diffCoverage());
        TeamStatistics team = service.teamStatistics(teamScope(), range, scan, false, false);
        assertTrue(team.dataReady());
        assertEquals(1, team.members().get(0).commitCount());
        assertTrue(team.coverage().partial());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OK", "PENDING", "FAILED", "TOO_LARGE", "SKIPPED_UNMAPPED"})
    void apijarCountsCommitsButNeverContributesLinesOrCoverageFailures(String legacyStatus) {
        when(identityMapper.selectByScope(anyLong(), anyString(), anyLong()))
                .thenReturn(List.of(mapping("EMAIL", "e1", 1L, false)));
        CommitEntity special = commit("jar", "e1", "n1", "2026-10-02T10:00:00", legacyStatus, 9999, 8888, 99, "main");
        special.setDepotId(999L); special.setDepotName("online.base.apijar.service");
        CommitEntity normal = commit("normal", "e1", "n1", "2026-10-05T10:00:00", "OK", 100, 20, 2, "main");
        CommitEntity similar = commit("similar", "e1", "n1", "2026-10-06T10:00:00", "OK", 20, 5, 1, "main");
        similar.setDepotId(998L); similar.setDepotName("online.base.apijar.service-extra");
        when(commitMapper.selectForRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(special, normal, similar));
        SelfStatistics mine = service.selfStatistics(selfScope(), range, completedScan(), false, true);
        assertEquals(3, mine.summary().commitCount());
        assertEquals(120, mine.summary().additions());
        assertTrue(mine.repositories().stream().filter(repo -> repo.depotName().equals("online.base.apijar.service"))
                .findFirst().orElseThrow().lineStatsExcluded());
        assertFalse(mine.repositories().stream().filter(repo -> repo.depotName().equals("online.base.apijar.service-extra"))
                .findFirst().orElseThrow().lineStatsExcluded());
        assertEquals(25, mine.summary().deletions());
        assertEquals(120, mine.daily().stream().mapToLong(day -> day.additions()).sum());
        assertEquals(0, mine.coverage().failedDiffCount());
        assertEquals(1D, mine.coverage().diffCoverage());
        assertFalse(mine.coverage().partial());
        var entry = service.entries(selfScope(), range, 999L, null, 1, 20).items().get(0);
        assertEquals("SKIPPED_REPOSITORY", entry.diffStatus());
        assertNull(entry.additions()); assertNull(entry.deletions()); assertNull(entry.filesChanged());
        TeamStatistics team = service.teamStatistics(teamScope(), range, completedScan(), false, true);
        MemberMonthly developer = team.members().get(0);
        assertEquals(3, developer.commitCount());
        assertEquals(120, developer.additions());
        assertEquals(25, developer.deletions());
        assertEquals(0, team.coverage().failedDiffCount());
    }

    private CodeStatsModule.ScopeInfo selfScope() {
        return new CodeStatsModule.ScopeInfo(Scope.SELF, 1L, 100L, "g-iijw5014",
                "https://g-iijw5014.coding.net", "张明远",
                List.of(new CodeStatsModule.MemberCredential(1L, "张明远", "", "token")), false);
    }

    private CodeStatsModule.ScopeInfo teamScope() {
        return new CodeStatsModule.ScopeInfo(Scope.WORKBENCH_TEAM, 9L, 100L, "g-iijw5014",
                "https://g-iijw5014.coding.net", "找钢后端组",
                List.of(new CodeStatsModule.MemberCredential(1L, "张明远", "", "token1", CodingRole.BACKEND),
                        new CodeStatsModule.MemberCredential(2L, "李婷", "", "token2", CodingRole.FRONTEND),
                        new CodeStatsModule.MemberCredential(3L, "王强", "", "", CodingRole.BACKEND),
                        new CodeStatsModule.MemberCredential(4L, "测试成员", "", "token4", CodingRole.QA),
                        new CodeStatsModule.MemberCredential(5L, "产品成员", "", "token5", CodingRole.PRODUCT),
                        new CodeStatsModule.MemberCredential(6L, "角色未设置", "", "token6")), true);
    }

    private ScanState completedScan() {
        ScanMonth month = new ScanMonth("2026-10", 1L, "FULL", "COMPLETED", "DONE",
                new ScanProgress(0, 0, 0, 0, 0, 0, 0, 0), null, null, "2026-10-30T10:00:00+08:00",
                null, 0, null);
        return new ScanState("SELF", null, range.view(), "COMPLETED", List.of(month), 0);
    }

    private IdentityEntity mapping(String type, String key, Long memberId, boolean ignored) {
        IdentityEntity entity = new IdentityEntity();
        entity.setIdentityType(type);
        entity.setIdentityKey(key);
        entity.setCodingUserId(memberId);
        entity.setIgnored(ignored);
        return entity;
    }

    private CommitEntity withMessage(CommitEntity entity, String message) {
        entity.setMessage(message);
        return entity;
    }

    private CommitEntity commit(String sha, String emailHash, String nameKey, String commitAt, String diffStatus,
                                Integer additions, Integer deletions, Integer files, String branch) {
        CommitEntity entity = new CommitEntity();
        entity.setId((long) Math.abs(sha.hashCode()));
        entity.setCodingTeamId(100L);
        entity.setScopeType("SELF");
        entity.setScopeId(1L);
        entity.setMonth("2026-10");
        entity.setDepotId((long) (sha.equals("s3") || sha.equals("s2") ? 2 : 1));
        entity.setDepotName(sha.equals("s3") || sha.equals("s2") ? "order-service" : "inventory-service");
        entity.setProjectName("iw-mixes");
        entity.setProjectDisplayName("IW 系统");
        entity.setSha(sha);
        entity.setShortSha(sha);
        entity.setAuthorName("作者" + sha);
        entity.setAuthorEmailHash(emailHash);
        entity.setAuthorNameKey(nameKey);
        LocalDateTime time = LocalDateTime.parse(commitAt);
        entity.setCommitAt(time);
        entity.setCommitDate(time.toLocalDate());
        entity.setMessage("提交 " + sha);
        entity.setBranchNames(CodeStatsSupport.toBranchJson(List.of(branch)));
        entity.setBranchCount(1);
        entity.setBranchTruncated(false);
        entity.setAdditions(additions == null ? null : additions.longValue());
        entity.setDeletions(deletions == null ? null : deletions.longValue());
        entity.setFilesChanged(files);
        entity.setDiffStatus(diffStatus);
        entity.setIsMerge(false);
        entity.setCommitUrl("https://example.com/commit/" + sha);
        entity.setLastSeenScanId(1L);
        return entity;
    }
}
