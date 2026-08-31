package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultWorklogModuleTest {

    private final WorklogModule.Context context = new WorklogModule.Context("token", 100L, "管理员", "avatar",
            10L, "g-iijw5014", "https://g-iijw5014.coding.net");

    @Test
    void workbenchTeamScopeRequiresExplicitSelection() {
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        WorklogQueryService queryService = mock(WorklogQueryService.class);
        WorklogViewCache cache = mock(WorklogViewCache.class);
        DefaultWorklogModule module = new DefaultWorklogModule(directory, queryService, cache);

        assertThatThrownBy(() -> module.statistics(context, "2026-08", "WORKBENCH_TEAM", null, false))
                .isInstanceOf(CodingOpenApiException.class).hasMessage("团队工时必须选择工作台团队");
        assertThatThrownBy(() -> module.entries(context, "2026-08-01", "2026-08-07", "WORKBENCH_TEAM", null, false))
                .isInstanceOf(CodingOpenApiException.class).hasMessage("团队工时必须选择工作台团队");
        verifyNoInteractions(directory, queryService, cache);
    }

    @Test
    void statisticsUsesMonthSpecificCacheAndQuery() {
        WorklogScopeDirectory directory = mock(WorklogScopeDirectory.class);
        WorklogQueryService queryService = mock(WorklogQueryService.class);
        WorklogViewCache cache = mock(WorklogViewCache.class);
        Team team = new Team(1L, "研发团队", "https://g-iijw5014.coding.net");
        List<WorklogModule.MemberCredential> members = List.of(
                new WorklogModule.MemberCredential(100L, "管理员", "avatar-a", "token-a"));
        Schedule schedule = WorkCalendarDefaults.schedule(YearMonth.of(2026, 8));
        WorkCalendarResolver calendar = (codingTeamId, month) -> schedule;
        when(directory.workbenchTeam(context, 1L)).thenReturn(new WorklogScopeDirectory.TeamSelection(team, members));
        Statistics fresh = statistics();
        when(cache.getStatistics(context, Scope.WORKBENCH_TEAM, 1L, YearMonth.of(2026, 8), 0, members))
                .thenReturn(Optional.empty());
        when(queryService.queryStatistics(context, team, Scope.WORKBENCH_TEAM, 1L, members,
                YearMonth.of(2026, 8), schedule))
                .thenReturn(fresh);
        DefaultWorklogModule module = new DefaultWorklogModule(directory, queryService, cache, calendar);

        assertThat(module.statistics(context, "2026-08", "WORKBENCH_TEAM", 1L, false)).isSameAs(fresh);
        verify(cache).putStatistics(context, Scope.WORKBENCH_TEAM, 1L, YearMonth.of(2026, 8), 0, members, fresh);
    }

    @Test
    void entriesRejectRangesLongerThan31Days() {
        DefaultWorklogModule module = new DefaultWorklogModule(mock(WorklogScopeDirectory.class),
                mock(WorklogQueryService.class), mock(WorklogViewCache.class));

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
        when(cache.getEntries(context, Scope.SELF, 100L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members))
                .thenReturn(Optional.empty());
        when(queryService.queryEntries(context, team, Scope.SELF, null, members,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8))).thenReturn(fresh);
        DefaultWorklogModule module = new DefaultWorklogModule(directory, queryService, cache);

        assertThat(module.entries(context, "2026-08-01", "2026-08-07", "SELF", null, false)).isSameAs(fresh);
        verify(cache).putEntries(context, Scope.SELF, 100L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), members, fresh);
        verify(cache, never()).putStatistics(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Statistics statistics() {
        return new Statistics(new Coverage(Scope.WORKBENCH_TEAM, 1L, 1, 0, false, 0, ""), "2026-08",
                "2026-08-26T12:00:00+08:00", List.of(), List.of());
    }

    private Entries entries() {
        return new Entries(new Coverage(Scope.SELF, null, 1, 0, false, 0, ""), "2026-08-01", "2026-08-08",
                "2026-08-26T12:00:00+08:00", BigDecimal.ZERO, List.of());
    }
}
