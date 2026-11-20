package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.EntriesPage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MonthlySnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.RangeReport;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueCheckStatus;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private WorklogMirror mirror;

    @org.springframework.beans.factory.annotation.Autowired
    DefaultWorklogModule(WorklogScopeDirectory directory, WorklogQueryService queries, WorklogViewCache cache,
                         WorkCalendarResolver calendar, PersonalLeaveResolver leaves, WorklogDeletedIssues deleted,
                         WorklogIssueChecks checks, WorklogMirror mirror) {
        this(directory, queries, cache, calendar, leaves, deleted, checks);
        this.mirror = mirror;
    }

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
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RangeReport report(Context context, String fromValue, String toValue, String scopeValue,
                              Long workbenchTeamId, boolean refresh) {
        EntryRange range = entryRange(fromValue, toValue);
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.SELF && workbenchTeamId != null) throw new CodingOpenApiException("个人工时不接受团队参数");
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        List<MemberCredential> members = selection.members();
        List<Day> days = new ArrayList<>();
        Map<Long, Set<LocalDate>> leaveDates = new LinkedHashMap<>();
        StringBuilder calendarVersions = new StringBuilder();
        int calendarVersion = 0;
        for (YearMonth month = YearMonth.from(range.from()); !month.isAfter(YearMonth.from(range.to()));
             month = month.plusMonths(1)) {
            Schedule schedule = calendar.resolve(context.codingTeamId(), month);
            days.addAll(schedule.days());
            calendarVersions.append(month).append('=').append(schedule.versionNo()).append(';');
            calendarVersion = Math.max(calendarVersion, schedule.versionNo());
            personalLeave.resolve(context.codingTeamId(), members.stream().map(MemberCredential::userId).toList(), month)
                    .forEach((userId, dates) -> leaveDates.computeIfAbsent(userId,
                            ignored -> new LinkedHashSet<>()).addAll(dates));
        }
        Schedule schedule = new Schedule(range.from() + ":" + range.to(), calendarVersion, days);
        LocalDate today = queryService.today();
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        long revision = deletedIssues.revision(context.codingTeamId());
        String key = cache.rangeKey(context, scope, scopeId, range.from(), range.to(), today,
                calendarVersions.toString(), leaveDates, members, revision) + dataVersion(context, workbenchTeamId, members);
        return cache.loadRange(key, refresh, () -> queryService.queryRange(context, scope, workbenchTeamId,
                members, range.from(), range.to().plusDays(1), schedule, leaveDates, today, revision, refresh));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Statistics statistics(Context context, String month, String scopeValue, Long workbenchTeamId, boolean refresh) {
        YearMonth yearMonth = parseMonth(month);
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.SELF && workbenchTeamId != null) throw new CodingOpenApiException("个人工时不接受团队参数");
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        return monthlySnapshot(context, yearMonth, scope, workbenchTeamId, selection, queryService.today(), refresh)
                .statistics();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Entries entries(Context context, String fromValue, String toValue, String scopeValue,
                           Long workbenchTeamId, boolean refresh) {
        EntryRange range = entryRange(fromValue, toValue);
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.SELF && workbenchTeamId != null) throw new CodingOpenApiException("个人工时不接受团队参数");
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        long revision = deletedIssues.revision(context.codingTeamId());
        String key = cache.entriesKey(context, scope, scopeId, range.from(), range.to(), selection.members(), revision) + dataVersion(context, workbenchTeamId, selection.members());
        if (!refresh) {
            Optional<Entries> cached = cache.getEntries(key);
            if (cached.isPresent()) return cached.get();
        }
        Entries result = queryService.queryEntries(context, selection.team(), scope, workbenchTeamId,
                selection.members(), range.from(), range.to().plusDays(1), revision, refresh);
        cache.putEntries(key, result);
        return result;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public EntriesPage entriesPage(Context context, String fromValue, String toValue, String scopeValue,
                                   Long workbenchTeamId, Long memberUserId, String cursor, boolean refresh) {
        var after = WorklogQueryService.EntryPosition.decode(cursor);
        EntryRange range = entryRange(fromValue, toValue);
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.SELF && workbenchTeamId != null) throw new CodingOpenApiException("个人工时不接受团队参数");
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        if (memberUserId != null && (scope != Scope.WORKBENCH_TEAM
                || selection.members().stream().noneMatch(member -> member.userId() == memberUserId))) {
            throw new CodingOpenApiException("请选择参与团队工时统计的成员");
        }
        long revision = deletedIssues.revision(context.codingTeamId());
        if (mirror != null) return queryService.pageLocalEntries(context, selection.team(), scope, workbenchTeamId,
                selection.members(), range.from(), range.to().plusDays(1), memberUserId, after, revision);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        String key = cache.entriesKey(context, scope, scopeId, range.from(), range.to(), selection.members(), revision)
                + ":snapshot:" + queryService.today();
        LocalDate toExclusive = range.to().plusDays(1);
        var snapshot = cache.loadEntriesSnapshot(key, refresh, () -> queryService.collectEntries(context, scope,
                workbenchTeamId, selection.members(), range.from(), toExclusive));
        return queryService.pageEntries(context, selection.team(), selection.members(), range.from(), toExclusive,
                snapshot, memberUserId, after, revision, refresh);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Absence absences(Context context, String month, String scopeValue, Long workbenchTeamId, boolean refresh) {
        YearMonth yearMonth = parseMonth(month);
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.SELF && workbenchTeamId != null) throw new CodingOpenApiException("个人工时不接受团队参数");
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
                members, revision) + dataVersion(context, workbenchTeamId, members);
        return cache.loadMonth(key, refresh, () -> queryService.queryMonth(context, scope, workbenchTeamId,
                members, month, schedule, leaveDates, today, revision, refresh));
    }

    @Override
    public IssueCheckStatus issueCheck(Context context, String scopeValue, Long workbenchTeamId, String checkId) {
        Scope scope = parseScope(scopeValue);
        if (scope == Scope.WORKBENCH_TEAM) {
            scopeDirectory.requireWorkbenchTeam(context, requireTeamId(workbenchTeamId));
        }
        var progress = issueChecks.progress(context, scope == Scope.WORKBENCH_TEAM ? workbenchTeamId : null, checkId);
        return new IssueCheckStatus(deletedIssues.revision(context.codingTeamId()),
                progress.pending(), progress.updates(), progress.incomplete());
    }

    private String dataVersion(Context context, Long teamId, List<MemberCredential> members) {
        return mirror == null ? "" : ":mirror:" + mirror.version(context, teamId, members);
    }

    @Override
    public WorklogModels.SyncStatus sync(Context context, String scopeValue, Long teamId, boolean trigger) {
        Scope scope = parseScope(scopeValue);
        TeamSelection selected = selection(context, scope, teamId);
        Long effectiveTeam = scope == Scope.SELF ? null : teamId;
        return trigger ? mirror.request(context, effectiveTeam, selected.members()) : mirror.status(context, effectiveTeam, selected.members());
    }

    private TeamSelection selection(Context context, Scope scope, Long workbenchTeamId) {
        if (scope == Scope.SELF) {
            Team team = scopeDirectory.selfTeam(context);
            WorklogModule.MemberCredential self = mirror == null ? new WorklogModule.MemberCredential(context.userId(),
                    context.userName(), context.avatar(), context.token()) : scopeDirectory.self(context);
            return new TeamSelection(team, List.of(self));
        }
        WorklogScopeDirectory.TeamSelection selection = scopeDirectory.workbenchTeam(context,
                requireTeamId(workbenchTeamId));
        return new TeamSelection(selection.team(), selection.members());
    }

    private long requireTeamId(Long workbenchTeamId) {
        if (workbenchTeamId == null || workbenchTeamId <= 0) {
            throw new CodingOpenApiException("团队工时必须选择 Dev Hub 团队");
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

    private EntryRange entryRange(String fromValue, String toValue) {
        LocalDate from = parseDate(fromValue, "开始日期");
        LocalDate to = parseDate(toValue, "结束日期");
        if (to.isBefore(from)) {
            throw new CodingOpenApiException("工时登记结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > 30) {
            throw new CodingOpenApiException("工时登记时间范围最多选择一个月（31天）");
        }
        return new EntryRange(from, to);
    }

    private record EntryRange(LocalDate from, LocalDate to) {
    }

    private record TeamSelection(Team team, List<WorklogModule.MemberCredential> members) {
    }
}
