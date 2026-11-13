package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MonthlySnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueCheckStatus;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
class DefaultWorklogModule implements WorklogModule {

    private final WorklogScopeDirectory scopeDirectory;

    private final WorklogQueryService queryService;

    private final WorklogViewCache cache;

    private final WorkCalendarResolver calendar;

    private final PersonalLeaveResolver personalLeave;
    private final WorklogDeletedIssues deletedIssues;
    private final WorklogIssueChecks issueChecks;

    DefaultWorklogModule(WorklogScopeDirectory scopeDirectory, WorklogQueryService queryService,
                         WorklogViewCache cache, WorkCalendarResolver calendar, PersonalLeaveResolver personalLeave,
                         WorklogDeletedIssues deletedIssues, WorklogIssueChecks issueChecks) {
        this.scopeDirectory = scopeDirectory;
        this.queryService = queryService;
        this.cache = cache;
        this.calendar = calendar;
        this.personalLeave = personalLeave;
        this.deletedIssues = deletedIssues;
        this.issueChecks = issueChecks;
    }

    @Override
    public Options options(Context context) {
        return scopeDirectory.options(context);
    }

    @Override
    public Statistics statistics(Context context, String month, String scopeValue, Long workbenchTeamId, boolean refresh) {
        YearMonth yearMonth = parseMonth(month);
        Scope scope = parseScope(scopeValue);
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        return monthlySnapshot(context, yearMonth, scope, workbenchTeamId, selection, queryService.today(), refresh)
                .statistics();
    }

    @Override
    public Entries entries(Context context, String fromValue, String toValue, String scopeValue,
                           Long workbenchTeamId, boolean refresh) {
        LocalDate from = parseDate(fromValue, "开始日期");
        LocalDate to = parseDate(toValue, "结束日期");
        if (to.isBefore(from)) {
            throw new CodingOpenApiException("工时登记结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > 30) {
            throw new CodingOpenApiException("工时登记时间范围最多选择一个月（31天）");
        }
        Scope scope = parseScope(scopeValue);
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        long revision = deletedIssues.revision(context.codingTeamId());
        String key = cache.entriesKey(context, scope, scopeId, from, to, selection.members(), revision);
        if (!refresh) {
            Optional<Entries> cached = cache.getEntries(key);
            if (cached.isPresent()) return cached.get();
        }
        Entries result = queryService.queryEntries(context, selection.team(), scope, workbenchTeamId,
                selection.members(), from, to.plusDays(1), revision, refresh);
        cache.putEntries(key, result);
        return result;
    }

    @Override
    public Absence absences(Context context, String month, String scopeValue, Long workbenchTeamId, boolean refresh) {
        YearMonth yearMonth = parseMonth(month);
        Scope scope = parseScope(scopeValue);
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        LocalDate today = queryService.today();
        LocalDate absenceEnd = queryService.absenceEnd(yearMonth, today);
        if (absenceEnd.equals(yearMonth.atDay(1))) {
            return queryService.emptyAbsence(scope, workbenchTeamId, yearMonth, absenceEnd);
        }
        return monthlySnapshot(context, yearMonth, scope, workbenchTeamId, selection, today, refresh).absences();
    }

    private MonthlySnapshot monthlySnapshot(Context context, YearMonth month, Scope scope, Long workbenchTeamId,
                                            TeamSelection selection, LocalDate today, boolean refresh) {
        Schedule schedule = calendar.resolve(context.codingTeamId(), month);
        List<MemberCredential> members = selection.members();
        Map<Long, Set<LocalDate>> leaveDates = personalLeave.resolve(context.codingTeamId(),
                members.stream().map(MemberCredential::userId).toList(), month);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        long revision = deletedIssues.revision(context.codingTeamId());
        String key = cache.monthlyKey(context, scope, scopeId, month, today, schedule.versionNo(), leaveDates,
                members, revision);
        return cache.loadMonth(key, refresh, () -> queryService.queryMonth(context, scope, workbenchTeamId,
                members, month, schedule, leaveDates, today, revision, refresh));
    }

    @Override
    public IssueCheckStatus issueCheck(Context context, String scopeValue, Long workbenchTeamId, String checkId) {
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.WORKBENCH_TEAM) {
            scopeDirectory.requireWorkbenchTeam(context, requireTeamId(workbenchTeamId));
        }
        boolean pending = issueChecks.isPending(context, scope == Scope.WORKBENCH_TEAM ? workbenchTeamId : null, checkId);
        return new IssueCheckStatus(deletedIssues.revision(context.codingTeamId()), pending);
    }

    private TeamSelection selection(Context context, Scope scope, Long workbenchTeamId) {
        if (scope == Scope.SELF) {
            Team team = scopeDirectory.selfTeam(context);
            WorklogModule.MemberCredential self = new WorklogModule.MemberCredential(context.userId(),
                    context.userName(), context.avatar(), context.token());
            return new TeamSelection(team, List.of(self));
        }
        WorklogScopeDirectory.TeamSelection selection = scopeDirectory.workbenchTeam(context,
                requireTeamId(workbenchTeamId));
        return new TeamSelection(selection.team(), selection.members());
    }

    private long requireTeamId(Long workbenchTeamId) {
        if (workbenchTeamId == null || workbenchTeamId <= 0) {
            throw new CodingOpenApiException("团队工时必须选择工作台团队");
        }
        return workbenchTeamId;
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new CodingOpenApiException("月份参数格式应为 YYYY-MM");
        }
    }

    private Scope parseScope(String value) {
        try {
            return Scope.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CodingOpenApiException("工时范围只支持 SELF 或 WORKBENCH_TEAM");
        }
    }

    private LocalDate parseDate(String value, String label) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new CodingOpenApiException(label + "格式应为 YYYY-MM-DD");
        }
    }

    private record TeamSelection(Team team, List<WorklogModule.MemberCredential> members) {
    }
}
