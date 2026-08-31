package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarDefaults;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver.LeaveSchedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
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

    private final boolean personalLeaveEnabled;

    DefaultWorklogModule(WorklogScopeDirectory scopeDirectory, WorklogQueryService queryService,
                         WorklogViewCache cache) {
        this(scopeDirectory, queryService, cache, (codingTeamId, month) -> WorkCalendarDefaults.schedule(month),
                (codingTeamId, codingUserId, month) -> LeaveSchedule.empty(), false);
    }

    DefaultWorklogModule(WorklogScopeDirectory scopeDirectory, WorklogQueryService queryService,
                         WorklogViewCache cache, WorkCalendarResolver calendar) {
        this(scopeDirectory, queryService, cache, calendar, (codingTeamId, codingUserId, month) -> LeaveSchedule.empty(), false);
    }

    @Autowired
    DefaultWorklogModule(WorklogScopeDirectory scopeDirectory, WorklogQueryService queryService,
                         WorklogViewCache cache, WorkCalendarResolver calendar,
                         PersonalLeaveResolver personalLeave) {
        this(scopeDirectory, queryService, cache, calendar, personalLeave, true);
    }

    private DefaultWorklogModule(WorklogScopeDirectory scopeDirectory, WorklogQueryService queryService,
                                 WorklogViewCache cache, WorkCalendarResolver calendar,
                                 PersonalLeaveResolver personalLeave, boolean personalLeaveEnabled) {
        this.scopeDirectory = scopeDirectory;
        this.queryService = queryService;
        this.cache = cache;
        this.calendar = calendar;
        this.personalLeave = personalLeave;
        this.personalLeaveEnabled = personalLeaveEnabled;
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
        Schedule schedule = calendar.resolve(context.codingTeamId(), yearMonth);
        if (!personalLeaveEnabled) {
            long legacyScopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
            if (!refresh) {
                Optional<Statistics> cached = cache.getStatistics(context, scope, legacyScopeId, yearMonth,
                        schedule.versionNo(), selection.members());
                if (cached.isPresent()) return cached.get();
            }
            Statistics result = queryService.queryStatistics(context, selection.team(), scope, workbenchTeamId,
                    selection.members(), yearMonth, schedule);
            cache.putStatistics(context, scope, legacyScopeId, yearMonth, schedule.versionNo(), selection.members(), result);
            return result;
        }
        LeaveResolution leaves = leaveResolution(context, selection.members(), yearMonth);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        if (!refresh) {
            Optional<Statistics> cached = cache.getStatistics(context, scope, scopeId, yearMonth,
                    schedule.versionNo(), leaves.versionNo(), selection.members());
            if (cached.isPresent()) return cached.get();
        }
        Statistics result = queryService.queryStatistics(context, selection.team(), scope, workbenchTeamId,
                selection.members(), yearMonth, schedule, leaves.datesByMember());
        cache.putStatistics(context, scope, scopeId, yearMonth, schedule.versionNo(), leaves.versionNo(),
                selection.members(), result);
        return result;
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
        if (!refresh) {
            Optional<Entries> cached = cache.getEntries(context, scope, scopeId, from, to, selection.members());
            if (cached.isPresent()) return cached.get();
        }
        Entries result = queryService.queryEntries(context, selection.team(), scope, workbenchTeamId,
                selection.members(), from, to.plusDays(1));
        cache.putEntries(context, scope, scopeId, from, to, selection.members(), result);
        return result;
    }

    @Override
    public Absence absences(Context context, String month, String scopeValue, Long workbenchTeamId, boolean refresh) {
        YearMonth yearMonth = parseMonth(month);
        Scope scope = parseScope(scopeValue);
        TeamSelection selection = selection(context, scope, workbenchTeamId);
        Schedule schedule = calendar.resolve(context.codingTeamId(), yearMonth);
        if (!personalLeaveEnabled) {
            long legacyScopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
            if (!refresh) {
                Optional<Absence> cached = cache.getAbsences(context, scope, legacyScopeId, yearMonth,
                        schedule.versionNo(), selection.members());
                if (cached.isPresent()) return cached.get();
            }
            Absence result = queryService.queryAbsences(context, selection.team(), scope, workbenchTeamId,
                    selection.members(), yearMonth, schedule);
            cache.putAbsences(context, scope, legacyScopeId, yearMonth, schedule.versionNo(), selection.members(), result);
            return result;
        }
        LeaveResolution leaves = leaveResolution(context, selection.members(), yearMonth);
        long scopeId = scope == Scope.SELF ? context.userId() : workbenchTeamId;
        if (!refresh) {
            Optional<Absence> cached = cache.getAbsences(context, scope, scopeId, yearMonth,
                    schedule.versionNo(), leaves.versionNo(), selection.members());
            if (cached.isPresent()) return cached.get();
        }
        Absence result = queryService.queryAbsences(context, selection.team(), scope, workbenchTeamId,
                selection.members(), yearMonth, schedule, leaves.datesByMember());
        cache.putAbsences(context, scope, scopeId, yearMonth, schedule.versionNo(), leaves.versionNo(),
                selection.members(), result);
        return result;
    }

    private TeamSelection selection(Context context, Scope scope, Long workbenchTeamId) {
        if (scope == Scope.SELF) {
            Team team = scopeDirectory.selfTeam(context);
            WorklogModule.MemberCredential self = new WorklogModule.MemberCredential(context.userId(),
                    context.userName(), context.avatar(), context.token());
            return new TeamSelection(team, List.of(self));
        }
        if (workbenchTeamId == null || workbenchTeamId <= 0) {
            throw new CodingOpenApiException("团队工时必须选择工作台团队");
        }
        WorklogScopeDirectory.TeamSelection selection = scopeDirectory.workbenchTeam(context, workbenchTeamId);
        return new TeamSelection(selection.team(), selection.members());
    }

    private LeaveResolution leaveResolution(Context context, List<WorklogModule.MemberCredential> members,
                                             YearMonth month) {
        Map<Long, Set<LocalDate>> datesByMember = new LinkedHashMap<>();
        Map<Long, Integer> versions = new LinkedHashMap<>();
        members.forEach(member -> {
            LeaveSchedule schedule = personalLeave.resolve(context.codingTeamId(), member.userId(), month);
            datesByMember.put(member.userId(), schedule.dates());
            versions.put(member.userId(), schedule.versionNo());
        });
        String material = versions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .reduce((left, right) -> left + "|" + right).orElse("");
        return new LeaveResolution(material.hashCode(), datesByMember);
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

    private record LeaveResolution(int versionNo, Map<Long, Set<LocalDate>> datesByMember) {
    }
}
