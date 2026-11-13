package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.WorklogPage;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultWorklogModuleTest {

    private final WorklogDeletedIssues deletedIssues = mock(WorklogDeletedIssues.class);
    private final WorklogIssueChecks issueChecks = mock(WorklogIssueChecks.class);
    private final WorklogModule.Context context = new WorklogModule.Context("token", 100L, "管理员", "avatar",
            10L, "g-iijw5014", "https://g-iijw5014.coding.net");

    @Test
    void workbenchTeamScopeRequiresExplicitSelection() {
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        WorklogQueryService queryService = mock(WorklogQueryService.class);
        WorklogViewCache cache = mock(WorklogViewCache.class);
        DefaultWorklogModule module = module(directory, queryService, cache);

        assertThatThrownBy(() -> module.statistics(context, "2026-08", "WORKBENCH_TEAM", null, false))
                .isInstanceOf(CodingOpenApiException.class).hasMessage("团队工时必须选择工作台团队");
        assertThatThrownBy(() -> module.entries(context, "2026-08-01", "2026-08-07", "WORKBENCH_TEAM", null, false))
                .isInstanceOf(CodingOpenApiException.class).hasMessage("团队工时必须选择工作台团队");
        verifyNoInteractions(directory, queryService, cache);
    }

    @Test
    void monthlyStatisticsAndAbsenceReuseCodingResultsAndRefreshTogether() {
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        WorklogQueryService queryService = queryService(coding);
        Team team = new Team(1L, "研发团队", "https://fixture.invalid");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(100L, "管理员", "", "member-token"),
                new WorklogModule.MemberCredential(200L, "未绑定成员", "", ""));
        when(directory.workbenchTeam(context, 1L)).thenReturn(new WorklogScopeDirectory.TeamSelection(team, members));

        long startAt = Instant.parse("2026-08-24T04:00:00Z").toEpochMilli();
        when(coding.worklogPage(eq("member-token"), anyLong(), anyLong(), eq(100L), eq(0), eq(1000)))
                .thenReturn(new WorklogPage(List.of(worklog(startAt, 7))), new WorklogPage(List.of(worklog(startAt, 8))));
        DefaultWorklogModule module = module(directory, queryService, redisCache());

        var statistics = module.statistics(context, "2026-08", "WORKBENCH_TEAM", 1L, false);
        var absence = module.absences(context, "2026-08", "WORKBENCH_TEAM", 1L, false);
        assertThat(absence.syncedAt()).isEqualTo(statistics.syncedAt());
        assertThat(absence.members()).extracting(member -> member.user().id()).containsExactly(100L);
        assertThat(absence.members().get(0).days()).extracting(day -> day.date()).contains("2026-08-24");
        assertThat(absence.toExclusive()).isEqualTo("2026-08-26");
        assertThat(absence.coverage().failedMemberCount()).isEqualTo(1);
        verify(coding).worklogPage(eq("member-token"), anyLong(), anyLong(), eq(100L), eq(0), eq(1000));

        module.statistics(context, "2026-08", "WORKBENCH_TEAM", 1L, true);
        var refreshedAbsence = module.absences(context, "2026-08", "WORKBENCH_TEAM", 1L, false);
        assertThat(refreshedAbsence.members().get(0).days()).extracting(day -> day.date())
                .doesNotContain("2026-08-24", "2026-08-26");
        verify(coding, times(2)).worklogPage(eq("member-token"), anyLong(), anyLong(), eq(100L), eq(0), eq(1000));
    }

    @Test
    void futureMonthReturnsEmptyAbsenceWithoutCallingCoding() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        when(directory.selfTeam(context)).thenReturn(new Team(10L, "测试", "https://fixture.invalid"));
        DefaultWorklogModule module = module(directory, queryService(coding), mock(WorklogViewCache.class));

        var absence = module.absences(context, "2026-09", "SELF", null, false);

        assertThat(absence.members()).isEmpty();
        assertThat(absence.from()).isEqualTo("2026-09-01");
        assertThat(absence.toExclusive()).isEqualTo("2026-09-01");
        verifyNoInteractions(coding);
    }

    @Test
    void entriesRejectRangesLongerThan31Days() {
        DefaultWorklogModule module = module(mock(WorklogScopeDirectory.class), mock(WorklogQueryService.class), mock(WorklogViewCache.class));

        assertThatThrownBy(() -> module.entries(context, "2026-08-01", "2026-09-01", "SELF", null, false))
                .isInstanceOf(CodingOpenApiException.class).hasMessage("工时登记时间范围最多选择一个月（31天）");
    }

    @Test
    void entriesUsesDateRangeSpecificCacheAndQuery() {
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        WorklogQueryService queryService = mock(WorklogQueryService.class);
        WorklogViewCache cache = mock(WorklogViewCache.class);
        Team team = new Team(1L, "产业数字中心", "https://g-iijw5014.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(100L, "管理员", "avatar", "token"));
        when(directory.selfTeam(context)).thenReturn(team);
        Entries fresh = entries();
        when(cache.entriesKey(context, Scope.SELF, 100L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7), members, 0L)).thenReturn("test-entries-key");
        when(cache.getEntries("test-entries-key")).thenReturn(Optional.empty());
        when(queryService.queryEntries(context, team, Scope.SELF, null, members,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8), 0L, false)).thenReturn(fresh);
        DefaultWorklogModule module = module(directory, queryService, cache);

        assertThat(module.entries(context, "2026-08-01", "2026-08-07", "SELF", null, false)).isSameAs(fresh);
        verify(cache).putEntries("test-entries-key", fresh);
    }

    private DefaultWorklogModule module(WorklogScopeDirectory directory, WorklogQueryService queryService,
                                        WorklogViewCache cache) {
        return new DefaultWorklogModule(directory, queryService, cache,
                (teamId, month) -> WorkCalendarDefaults.schedule(month), (teamId, users, month) -> Map.of(), deletedIssues, issueChecks);
    }

    private WorklogQueryService queryService(CodingOpenApiPort coding) {
        return new WorklogQueryService(coding, new CodingIssueLinkBuilder(),
                Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneId.of("Asia/Shanghai")),
                deletedIssues, issueChecks);
    }

    private Worklog worklog(long startAt, int hours) {
        return new Worklog(1L, 11L, 101L, "project-a", 100L, BigDecimal.valueOf(hours), "", startAt, startAt, startAt);
    }

    private WorklogViewCache redisCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> {
            store.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        return new WorklogViewCache(redis, new ObjectMapper().findAndRegisterModules(), new ZhaogangProperties());
    }

    private Entries entries() {
        return new Entries(new Coverage(Scope.SELF, null, 1, 0, false, 0, ""), "2026-08-01", "2026-08-08",
                "2026-08-26T12:00:00+08:00", BigDecimal.ZERO, List.of());
    }
}
