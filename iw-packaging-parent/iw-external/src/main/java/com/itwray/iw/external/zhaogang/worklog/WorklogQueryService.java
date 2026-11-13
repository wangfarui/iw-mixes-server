package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingPermissionError;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.DailyTotal;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.AbsenceDay;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Item;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MemberDailyTotal;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MemberAbsence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MemberIssue;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MonthlySnapshot;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Project;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueCheck;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Summary;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.User;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
class WorklogQueryService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 100;

    private final CodingOpenApiPort coding;
    private final CodingIssueLinkBuilder issueLinkBuilder;
    private final Clock clock;
    private final ExecutorService memberExecutor;
    private final boolean ownsExecutor;
    private final WorklogDeletedIssues deletedIssues;
    private final WorklogIssueChecks issueChecks;

    @Autowired
    WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder,
                        ZhaogangProperties properties, WorklogDeletedIssues deletedIssues, WorklogIssueChecks issueChecks) {
        this(coding, issueLinkBuilder, Clock.system(CHINA_ZONE),
                Executors.newFixedThreadPool(Math.max(1, properties.getWorklogExecutorConcurrency()),
                        daemonThreadFactory()), true, deletedIssues, issueChecks);
    }

    WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder, Clock clock,
                        WorklogDeletedIssues deletedIssues, WorklogIssueChecks issueChecks) {
        this(coding, issueLinkBuilder, clock, ForkJoinPool.commonPool(), false, deletedIssues, issueChecks);
    }

    private WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder, Clock clock,
                                ExecutorService memberExecutor, boolean ownsExecutor,
                                WorklogDeletedIssues deletedIssues, WorklogIssueChecks issueChecks) {
        this.coding = coding;
        this.issueLinkBuilder = issueLinkBuilder;
        this.clock = clock;
        this.memberExecutor = memberExecutor;
        this.ownsExecutor = ownsExecutor;
        this.deletedIssues = deletedIssues;
        this.issueChecks = issueChecks;
    }

    @PreDestroy
    void shutdown() {
        if (ownsExecutor) memberExecutor.shutdownNow();
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    MonthlySnapshot queryMonth(WorklogModule.Context context, Scope scope, Long workbenchTeamId,
                               List<WorklogModule.MemberCredential> requestedMembers, YearMonth month,
                               Schedule schedule, Map<Long, Set<LocalDate>> leaveDatesByMember, LocalDate today,
                               long deletionRevision, boolean refresh) {
        List<WorklogModule.MemberCredential> members = normalizeMembers(requestedMembers);
        LocalDate from = month.atDay(1);
        LocalDate monthEnd = month.plusMonths(1).atDay(1);
        LocalDate absenceEnd = absenceEnd(month, today);
        if (members.isEmpty() && scope == Scope.WORKBENCH_TEAM) {
            return new MonthlySnapshot(emptyStatistics(scope, workbenchTeamId, month, schedule),
                    emptyAbsence(scope, workbenchTeamId, month, absenceEnd));
        }

        long fetchStarted = System.nanoTime();
        List<MemberLogs> memberResults = fetchMembers(members, from, monthEnd, today);
        long lookupStarted = System.nanoTime();
        List<PendingWorklog> monthItems = worklogsInRange(memberResults, from, monthEnd);
        // 先按登记日期裁剪再去重，两个视图共享事项查验结果。
        List<PendingWorklog> absenceItems = absenceEnd.equals(monthEnd) ? monthItems
                : worklogsInRange(memberResults, from, absenceEnd);
        var candidates = absenceItems == monthItems ? monthItems.stream()
                : Stream.concat(monthItems.stream(), absenceItems.stream());
        Set<Long> deleted = findDeleted(context, candidates);
        List<PendingWorklog> monthWorklogs = excludeDeleted(monthItems, deleted);
        List<PendingWorklog> absenceWorklogs = absenceItems == monthItems ? monthWorklogs
                : excludeDeleted(absenceItems, deleted);
        var check = enqueueCheck(context, scope == Scope.WORKBENCH_TEAM ? workbenchTeamId : null,
                absenceItems == monthItems ? monthWorklogs.stream()
                : Stream.concat(monthWorklogs.stream(), absenceWorklogs.stream()), deletionRevision, refresh);
        long aggregationStarted = System.nanoTime();
        String syncedAt = syncedAt();
        Statistics statistics = statistics(scope, workbenchTeamId, members, month, schedule, leaveDatesByMember,
                memberResults, monthWorklogs, from, monthEnd, today, syncedAt, check);
        Absence absences = absenceEnd.equals(from)
                ? emptyAbsence(scope, workbenchTeamId, month, absenceEnd)
                : absences(scope, workbenchTeamId, members, month, schedule, leaveDatesByMember,
                        memberResults, absenceWorklogs, from, absenceEnd, syncedAt, check);
        log.debug("工时月度快照 scope={} members={} fetchMs={} localFilterMs={} aggregationMs={}",
                scope, members.size(), TimeUnit.NANOSECONDS.toMillis(lookupStarted - fetchStarted),
                TimeUnit.NANOSECONDS.toMillis(aggregationStarted - lookupStarted),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - aggregationStarted));
        return new MonthlySnapshot(statistics, absences);
    }

    LocalDate absenceEnd(YearMonth month, LocalDate today) {
        LocalDate from = month.atDay(1);
        LocalDate monthEnd = month.plusMonths(1).atDay(1);
        if (today.isBefore(from)) return from;
        return today.isBefore(monthEnd) ? today : monthEnd;
    }

    private Statistics statistics(Scope scope, Long workbenchTeamId,
                                  List<WorklogModule.MemberCredential> members, YearMonth month, Schedule schedule,
                                  Map<Long, Set<LocalDate>> leaveDatesByMember, List<MemberLogs> memberResults,
                                  List<PendingWorklog> worklogs, LocalDate from, LocalDate toExclusive,
                                  LocalDate today, String syncedAt, IssueCheck check) {
        List<Worklog> allItems = distinctWorklogs(worklogs).stream()
                .map(PendingWorklog::worklog).toList();
        Map<Long, List<Worklog>> itemsByMember = worklogsByMember(worklogs);
        Map<Long, MemberLogs> resultsByMember = resultsByMember(memberResults);
        List<MemberDailyTotal> memberDailyTotals = scope == Scope.WORKBENCH_TEAM
                ? members.stream().filter(member -> !resultsByMember.get(member.userId()).failed())
                .map(member -> {
                    List<DailyTotal> totals = dailyTotals(
                            itemsByMember.getOrDefault(member.userId(), List.of()),
                            from, toExclusive, schedule);
                    return new MemberDailyTotal(new User(member.userId(), member.userName(), member.avatar()),
                            totals, summary(totals, leaveDatesByMember.getOrDefault(member.userId(), Set.of()), today));
                })
                .toList()
                : List.of();
        List<DailyTotal> dailyTotals = dailyTotals(allItems, from, toExclusive, schedule);
        Coverage coverage = coverage(scope, workbenchTeamId, members, memberResults, projectCount(allItems));
        int aggregateWorkdayCount = members.stream()
                .filter(member -> {
                    MemberLogs result = resultsByMember.get(member.userId());
                    return result != null && !result.failed() && !result.partial();
                })
                .mapToInt(member -> eligibleWorkdayCount(schedule,
                        leaveDatesByMember.getOrDefault(member.userId(), Set.of()), from, toExclusive, today))
                .sum();
        Summary aggregateSummary = scope == Scope.WORKBENCH_TEAM
                ? aggregateMemberSummaries(memberDailyTotals, dailyTotals, aggregateWorkdayCount)
                : summary(dailyTotals, aggregateWorkdayCount);
        return new Statistics(coverage, month.toString(), schedule.versionNo(), syncedAt,
                aggregateSummary,
                dailyTotals, memberDailyTotals, check);
    }

    Entries queryEntries(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                         List<WorklogModule.MemberCredential> requestedMembers, LocalDate from,
                         LocalDate toExclusive, long deletionRevision, boolean refresh) {
        List<WorklogModule.MemberCredential> members = normalizeMembers(requestedMembers);
        if (members.isEmpty() && scope == Scope.WORKBENCH_TEAM) {
            return emptyEntries(scope, workbenchTeamId, from, toExclusive);
        }
        List<MemberLogs> memberResults = fetchMembers(members, from, toExclusive, today());
        List<PendingWorklog> items = worklogsInRange(memberResults, from, toExclusive);
        List<PendingWorklog> worklogs = distinctWorklogs(excludeDeleted(items, findDeleted(context, items.stream())));
        var check = enqueueCheck(context, scope == Scope.WORKBENCH_TEAM ? workbenchTeamId : null,
                worklogs.stream(), deletionRevision, refresh);
        Comparator<Worklog> byRegistrationTime = Comparator.comparingLong(this::sortStart).reversed()
                .thenComparing(Comparator.comparingLong(this::sortSecondary).reversed())
                .thenComparing(Comparator.comparingLong(Worklog::id).reversed());
        Map<Long, WorklogModule.MemberCredential> memberMap = members.stream().collect(LinkedHashMap::new,
                (map, member) -> map.put(member.userId(), member), LinkedHashMap::putAll);
        List<Item> entries = worklogs.stream()
                .sorted(Comparator.comparing(PendingWorklog::worklog, byRegistrationTime))
                .map(item -> toItem(context, team, memberMap, item.issueKey() == null ? null
                        : issueChecks.cachedIssue(item.token(), item.issueKey()), item.worklog()))
                .toList();
        BigDecimal totalHours = entries.stream().map(Item::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        Coverage coverage = coverage(scope, workbenchTeamId, members, memberResults,
                projectCount(worklogs.stream().map(PendingWorklog::worklog).toList()));
        return new Entries(coverage, from.toString(), toExclusive.toString(), syncedAt(),
                normalizeHours(totalHours), entries, check);
    }

    private Absence absences(Scope scope, Long workbenchTeamId,
                             List<WorklogModule.MemberCredential> members, YearMonth month, Schedule schedule,
                             Map<Long, Set<LocalDate>> leaveDatesByMember, List<MemberLogs> memberResults,
                             List<PendingWorklog> worklogs, LocalDate from, LocalDate rangeEnd, String syncedAt, IssueCheck check) {
        Map<Long, List<Worklog>> itemsByMember = worklogsByMember(worklogs);
        Map<Long, MemberLogs> resultsByMember = resultsByMember(memberResults);
        Map<Long, WorklogModule.MemberCredential> memberMap = members.stream().collect(LinkedHashMap::new,
                (map, member) -> map.put(member.userId(), member), LinkedHashMap::putAll);
        List<MemberAbsence> absentMembers = members.stream()
                .map(member -> resultsByMember.get(member.userId()))
                .filter(result -> result != null && !result.failed() && !result.partial())
                .map(result -> {
                    WorklogModule.MemberCredential member = memberMap.get(result.userId());
                    List<AbsenceDay> days = absenceDays(itemsByMember.getOrDefault(member.userId(), List.of()), from,
                            rangeEnd, schedule, leaveDatesByMember.getOrDefault(result.userId(), Set.of()));
                    return days.isEmpty() ? null : new MemberAbsence(
                            new User(member.userId(), member.userName(), member.avatar()), days.size(), days);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        Coverage coverage = coverage(scope, workbenchTeamId, members, memberResults,
                projectCount(worklogs.stream().map(PendingWorklog::worklog).toList()));
        return new Absence(coverage, month.toString(), from.toString(), rangeEnd.toString(), syncedAt, absentMembers, check);
    }

    private List<WorklogModule.MemberCredential> normalizeMembers(List<WorklogModule.MemberCredential> requestedMembers) {
        return (requestedMembers == null ? List.<WorklogModule.MemberCredential>of() : requestedMembers).stream()
                .filter(member -> member.userId() > 0)
                .collect(LinkedHashMap<Long, WorklogModule.MemberCredential>::new,
                        (map, member) -> map.putIfAbsent(member.userId(), member), LinkedHashMap::putAll)
                .values().stream().toList();
    }

    private List<MemberLogs> fetchMembers(List<WorklogModule.MemberCredential> members, LocalDate from,
                                          LocalDate toExclusive, LocalDate today) {
        // CODING 按提交时间返回记录。拉取至今天结束，才能覆盖历史区间内的补登工时。
        LocalDate tomorrow = today.plusDays(1);
        LocalDate fetchToExclusive = toExclusive.isBefore(tomorrow) ? tomorrow : toExclusive;
        long requestFrom = from.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli() - 1;
        long requestTo = fetchToExclusive.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli() + 1;
        List<CompletableFuture<MemberLogs>> futures = members.stream()
                .map(member -> CompletableFuture.supplyAsync(() -> fetchMember(member, requestFrom, requestTo), memberExecutor))
                .toList();
        List<MemberLogs> results = futures.stream().map(CompletableFuture::join).toList();
        int failedMembers = (int) results.stream().filter(MemberLogs::failed).count();
        if (failedMembers == results.size() && failedMembers > 0) {
            CodingOpenApiException permissionError = results.stream().map(MemberLogs::error)
                    .filter(CodingOpenApiException.class::isInstance).map(CodingOpenApiException.class::cast)
                    .filter(CodingOpenApiException::isPermissionDenied).findFirst().orElse(null);
            if (permissionError != null) throw permissionError;
            throw new CodingOpenApiException("工时加载失败，请稍后重试");
        }
        return results;
    }

    private Map<Long, List<Worklog>> worklogsByMember(List<PendingWorklog> items) {
        return items.stream().collect(Collectors.groupingBy(PendingWorklog::memberId,
                Collectors.mapping(PendingWorklog::worklog, Collectors.toList())));
    }

    private Map<Long, MemberLogs> resultsByMember(List<MemberLogs> results) {
        return results.stream().collect(LinkedHashMap::new,
                (map, result) -> map.put(result.userId(), result), LinkedHashMap::putAll);
    }

    private Coverage coverage(Scope scope, Long workbenchTeamId, List<WorklogModule.MemberCredential> members,
                              List<MemberLogs> results, int projectCount) {
        int failedMembers = (int) results.stream().filter(MemberLogs::failed).count();
        boolean partial = failedMembers > 0 || results.stream().anyMatch(MemberLogs::partial);
        CodingOpenApiException permissionError = results.stream().map(MemberLogs::error)
                .filter(CodingOpenApiException.class::isInstance).map(CodingOpenApiException.class::cast)
                .filter(CodingOpenApiException::isPermissionDenied)
                .findFirst().orElse(null);
        String permissionWarning = permissionError == null ? "" : permissionError.permissionMessage();
        String warning = StringUtils.isNotBlank(permissionWarning) ? permissionWarning : "";
        Map<Long, WorklogModule.MemberCredential> memberMap = members.stream().collect(LinkedHashMap::new,
                (map, member) -> map.put(member.userId(), member), LinkedHashMap::putAll);
        List<MemberIssue> memberIssues = results.stream().filter(MemberLogs::partial)
                .map(result -> memberIssue(memberMap.get(result.userId()), result))
                .toList();
        return new Coverage(scope, workbenchTeamId, members.size(), projectCount, partial, failedMembers, warning,
                permissionError == null ? null : CodingPermissionError.from(permissionError), memberIssues);
    }

    private MemberIssue memberIssue(WorklogModule.MemberCredential member, MemberLogs result) {
        User user = new User(member.userId(), member.userName(), member.avatar());
        if (StringUtils.isBlank(member.token())) {
            return new MemberIssue(user, "TOKEN_MISSING", "尚未绑定 CODING 令牌，请该成员在工作台登录并绑定令牌");
        }
        if (!result.failed()) {
            if (!result.pageLimitReached()) {
                return new MemberIssue(user, "MISSING_START_AT", "部分记录缺少登记时间，未计入工时");
            }
            return new MemberIssue(user, "INCOMPLETE", "工时记录达到查询页数上限，数据未完整取得");
        }
        RuntimeException error = result.error();
        if (error instanceof CodingOpenApiException codingError) {
            if (codingError.isPermissionDenied()) {
                return new MemberIssue(user, "PERMISSION_DENIED", codingError.permissionMessage());
            }
            if (codingError.isTransportFailure()) {
                return new MemberIssue(user, "REQUEST_FAILED", "与 CODING 服务通信失败，请稍后重试");
            }
            String code = codingError.code();
            if (StringUtils.isNotBlank(code) && code.matches("[A-Za-z0-9_-]{1,64}")) {
                return new MemberIssue(user, "CODING_ERROR", "CODING 工时接口返回错误（" + code + "），请稍后重试");
            }
        }
        return new MemberIssue(user, "REQUEST_FAILED", "工时获取失败，请稍后重试");
    }

    private int projectCount(List<Worklog> items) {
        Set<String> projects = new LinkedHashSet<>();
        items.stream().map(Worklog::projectName).filter(StringUtils::isNotBlank).forEach(projects::add);
        return projects.size();
    }

    private Statistics emptyStatistics(Scope scope, Long workbenchTeamId, YearMonth month, Schedule schedule) {
        List<DailyTotal> dailyTotals = schedule.days().stream()
                .map(day -> new DailyTotal(day.date().toString(), BigDecimal.ZERO, day.dayType())).toList();
        return new Statistics(new Coverage(scope, workbenchTeamId, 0, 0, false, 0, ""),
                month.toString(), schedule.versionNo(), syncedAt(), Summary.empty(), dailyTotals, List.of());
    }

    private Entries emptyEntries(Scope scope, Long workbenchTeamId, LocalDate from, LocalDate toExclusive) {
        return new Entries(new Coverage(scope, workbenchTeamId, 0, 0, false, 0, ""), from.toString(),
                toExclusive.toString(), syncedAt(), BigDecimal.ZERO, List.of());
    }

    Absence emptyAbsence(Scope scope, Long workbenchTeamId, YearMonth month, LocalDate toExclusive) {
        return new Absence(new Coverage(scope, workbenchTeamId, 0, 0, false, 0, ""), month.toString(),
                month.atDay(1).toString(), toExclusive.toString(), syncedAt(), List.of());
    }

    private String syncedAt() {
        return ZonedDateTime.now(clock).withZoneSameInstant(CHINA_ZONE)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private List<Worklog> deduplicate(List<Worklog> items, LocalDate from, LocalDate toExclusive) {
        Map<String, Worklog> deduplicated = new LinkedHashMap<>();
        items.stream().filter(item -> within(item, from, toExclusive))
                .forEach(item -> deduplicated.putIfAbsent(worklogKey(item), item));
        return new ArrayList<>(deduplicated.values());
    }

    private List<PendingWorklog> distinctWorklogs(List<PendingWorklog> items) {
        Map<String, PendingWorklog> deduplicated = new LinkedHashMap<>();
        items.forEach(item -> deduplicated.putIfAbsent(worklogKey(item.worklog()), item));
        return new ArrayList<>(deduplicated.values());
    }

    private List<DailyTotal> dailyTotals(List<Worklog> items, LocalDate from, LocalDate toExclusive,
                                         Schedule schedule) {
        Map<LocalDate, BigDecimal> daily = new LinkedHashMap<>();
        from.datesUntil(toExclusive).forEach(date -> daily.put(date, BigDecimal.ZERO));
        items.stream().filter(item -> within(item, from, toExclusive)).forEach(item ->
                daily.computeIfPresent(worklogDate(item), (date, hours) -> hours.add(normalizeHours(item.hours()))));
        Map<LocalDate, DayType> dayTypes = schedule.days().stream()
                .collect(LinkedHashMap::new, (map, day) -> map.put(day.date(), day.dayType()), LinkedHashMap::putAll);
        return daily.entrySet().stream()
                .map(entry -> new DailyTotal(entry.getKey().toString(), normalizeHours(entry.getValue()),
                        dayTypes.getOrDefault(entry.getKey(), DayType.REST_DAY)))
                .toList();
    }

    private List<AbsenceDay> absenceDays(List<Worklog> items, LocalDate from, LocalDate toExclusive,
                                         Schedule schedule, Set<LocalDate> leaveDates) {
        Map<LocalDate, BigDecimal> totals = dailyTotals(items, from, toExclusive, schedule).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(LocalDate.parse(item.date()), item.hours()),
                        LinkedHashMap::putAll);
        Set<LocalDate> workdays = schedule.days().stream()
                .filter(day -> day.dayType() == DayType.WORKDAY)
                .map(Day::date)
                .collect(java.util.stream.Collectors.toSet());
        BigDecimal threshold = BigDecimal.valueOf(8);
        return from.datesUntil(toExclusive)
                .filter(workdays::contains)
                .filter(date -> !leaveDates.contains(date))
                .filter(date -> totals.getOrDefault(date, BigDecimal.ZERO).compareTo(threshold) < 0)
                .map(date -> new AbsenceDay(date.toString(), normalizeHours(totals.getOrDefault(date, BigDecimal.ZERO))))
                .toList();
    }

    private Summary summary(List<DailyTotal> dailyTotals, Set<LocalDate> leaveDates, LocalDate today) {
        int workdayCount = (int) dailyTotals.stream()
                .filter(item -> item.dayType() == DayType.WORKDAY)
                .filter(item -> LocalDate.parse(item.date()).isBefore(today))
                .filter(item -> !leaveDates.contains(LocalDate.parse(item.date())))
                .count();
        return summary(dailyTotals, workdayCount);
    }

    private Summary summary(List<DailyTotal> dailyTotals, int workdayCount) {
        BigDecimal overtimeHours = BigDecimal.ZERO;
        BigDecimal totalHours = BigDecimal.ZERO;
        int overtimeDays = 0;
        for (DailyTotal item : dailyTotals) {
            BigDecimal hours = normalizeHours(item.hours());
            totalHours = totalHours.add(hours);
            if (item.dayType() == DayType.WORKDAY) {
                if (hours.compareTo(BigDecimal.valueOf(9)) >= 0) overtimeDays++;
                if (hours.compareTo(BigDecimal.valueOf(8)) > 0) {
                    overtimeHours = overtimeHours.add(hours.subtract(BigDecimal.valueOf(8)));
                }
            } else {
                if (hours.compareTo(BigDecimal.valueOf(4)) >= 0) overtimeDays++;
                overtimeHours = overtimeHours.add(hours);
            }
        }
        BigDecimal averageHours = workdayCount == 0 ? BigDecimal.ZERO
                : totalHours.divide(BigDecimal.valueOf(workdayCount), 2, java.math.RoundingMode.HALF_UP);
        return new Summary(overtimeDays, normalizeHours(overtimeHours), normalizeHours(averageHours));
    }

    private Summary aggregateMemberSummaries(List<MemberDailyTotal> memberDailyTotals,
                                             List<DailyTotal> dailyTotals, int workdayCount) {
        int overtimeDays = memberDailyTotals.stream()
                .map(MemberDailyTotal::summary)
                .mapToInt(Summary::overtimeDays)
                .sum();
        BigDecimal overtimeHours = memberDailyTotals.stream()
                .map(MemberDailyTotal::summary)
                .map(Summary::overtimeHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalHours = dailyTotals.stream()
                .map(DailyTotal::hours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageHours = workdayCount == 0 ? BigDecimal.ZERO
                : totalHours.divide(BigDecimal.valueOf(workdayCount), 2, java.math.RoundingMode.HALF_UP);
        return new Summary(overtimeDays, normalizeHours(overtimeHours), normalizeHours(averageHours));
    }

    private int eligibleWorkdayCount(Schedule schedule, Set<LocalDate> leaveDates,
                                     LocalDate from, LocalDate toExclusive, LocalDate today) {
        return (int) from.datesUntil(toExclusive)
                .filter(date -> date.isBefore(today))
                .filter(date -> !leaveDates.contains(date))
                .filter(date -> schedule.days().stream().anyMatch(day -> day.date().equals(date)
                        && day.dayType() == DayType.WORKDAY))
                .count();
    }

    private MemberLogs fetchMember(WorklogModule.MemberCredential member, long from, long to) {
        if (StringUtils.isBlank(member.token())) {
            return new MemberLogs(member.userId(), member.token(), List.of(), false, true,
                    new CodingOpenApiException("成员 " + member.userName() + " 尚未绑定 CODING 令牌"));
        }
        List<Worklog> result = new ArrayList<>();
        int offset = 0;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                List<Worklog> items = coding.worklogPage(member.token(), from, to, member.userId(), offset, PAGE_LIMIT).items();
                result.addAll(items);
                if (items.size() < PAGE_LIMIT) {
                    return new MemberLogs(member.userId(), member.token(), result, false, false, null);
                }
                offset += PAGE_LIMIT;
            }
            return new MemberLogs(member.userId(), member.token(), result, true, false, null);
        } catch (RuntimeException e) {
            return new MemberLogs(member.userId(), member.token(), List.of(), false, true, e);
        }
    }

    private List<PendingWorklog> worklogsInRange(List<MemberLogs> memberResults, LocalDate from,
                                                  LocalDate toExclusive) {
        List<PendingWorklog> items = new ArrayList<>();
        for (MemberLogs member : memberResults) {
            for (Worklog item : deduplicate(member.items(), from, toExclusive)) {
                IssueKey key = item.issueCode() > 0 && StringUtils.isNotBlank(item.projectName())
                        ? new IssueKey(item.projectName(), item.issueCode()) : null;
                items.add(new PendingWorklog(item, member.userId(), member.token(), key));
            }
        }
        return items;
    }

    private Set<Long> findDeleted(WorklogModule.Context context, Stream<PendingWorklog> items) {
        Set<Long> ids = items.map(item -> item.worklog().issueId()).filter(id -> id > 0)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : deletedIssues.find(context.codingTeamId(), ids);
    }

    private List<PendingWorklog> excludeDeleted(List<PendingWorklog> items, Set<Long> deleted) {
        return items.stream().filter(item -> !deleted.contains(item.worklog().issueId())).toList();
    }

    private IssueCheck enqueueCheck(WorklogModule.Context context, Long workbenchTeamId, Stream<PendingWorklog> items,
                                    long deletionRevision, boolean refresh) {
        Map<String, Map<IssueKey, Set<Long>>> requested = new LinkedHashMap<>();
        items.filter(item -> item.issueKey() != null && StringUtils.isNotBlank(item.token()))
                .forEach(item -> {
                    Set<Long> ids = requested.computeIfAbsent(item.token(), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(item.issueKey(), ignored -> new LinkedHashSet<>());
                    if (item.worklog().issueId() > 0) ids.add(item.worklog().issueId());
                });
        return new IssueCheck(deletionRevision, issueChecks.enqueue(context, workbenchTeamId, requested, refresh));
    }

    private Item toItem(WorklogModule.Context context, Team team,
                        Map<Long, WorklogModule.MemberCredential> members,
                        CodingOpenApiPort.Issue issue, Worklog item) {
        WorklogModule.MemberCredential member = members.get(item.userId());
        User user = member == null
                ? new User(item.userId(), item.userId() == context.userId() ? context.userName() : "成员 " + item.userId(),
                item.userId() == context.userId() ? context.avatar() : "")
                : new User(member.userId(), member.userName(), member.avatar());
        String title = issue == null ? "" : issue.title();
        if (StringUtils.isBlank(title)) title = StringUtils.defaultIfBlank(item.workingDesc(), "事项 #" + item.issueCode());
        String type = issue == null ? "" : issue.type();
        String typeName = issue == null ? "事项" : StringUtils.defaultIfBlank(issue.typeName(), issue.type());
        String displayName = issue == null ? item.projectName()
                : StringUtils.defaultIfBlank(issue.projectDisplayName(), item.projectName());
        return new Item(item.id(), user, formatTime(item.startAt()), formatTime(item.createdAt()),
                formatTime(item.updatedAt()), normalizeHours(item.hours()), item.workingDesc(),
                new Project(item.projectName(), displayName),
                new WorklogModels.Issue(item.issueCode(), type, typeName, title),
                issueLinkBuilder.build(team.host(), item.projectName(), item.issueCode(), issue));
    }

    private boolean within(Worklog item, LocalDate from, LocalDate toExclusive) {
        LocalDate date = worklogDate(item);
        return date != null && !date.isBefore(from) && date.isBefore(toExclusive);
    }

    private LocalDate worklogDate(Worklog item) {
        Long millis = registrationMillis(item);
        return millis == null ? null : Instant.ofEpochMilli(millis).atZone(CHINA_ZONE).toLocalDate();
    }

    private static Long registrationMillis(Worklog item) {
        return item.startAt() != null && item.startAt() > 0 ? item.startAt() : null;
    }

    private long sortStart(Worklog item) {
        return item.startAt();
    }

    private long sortSecondary(Worklog item) {
        Long value = item.updatedAt() != null ? item.updatedAt() : item.createdAt();
        return value == null ? 0 : value;
    }

    private String formatTime(Long epochMillis) {
        return epochMillis == null ? "" : DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.ofEpochMilli(epochMillis).atZone(CHINA_ZONE));
    }

    private BigDecimal normalizeHours(BigDecimal hours) {
        if (hours == null || hours.signum() <= 0) return BigDecimal.ZERO;
        return hours.stripTrailingZeros();
    }

    private String worklogKey(Worklog item) {
        if (item.id() > 0) return "id:" + item.id();
        return String.join(":", item.projectName(), String.valueOf(item.issueCode()), String.valueOf(item.userId()),
                String.valueOf(item.startAt()), String.valueOf(item.createdAt()), String.valueOf(item.hours()));
    }

    private record MemberLogs(long userId, String token, List<Worklog> items, boolean pageLimitReached, boolean failed,
                              RuntimeException error) {
        boolean partial() {
            return failed || pageLimitReached || items.stream().anyMatch(item -> registrationMillis(item) == null);
        }
    }

    private record PendingWorklog(Worklog worklog, long memberId, String token, IssueKey issueKey) {
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "zhaogang-worklog-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
