package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Coverage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.DailyTotal;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.AbsenceDay;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Item;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MemberDailyTotal;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.MemberAbsence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Project;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Summary;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.User;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class WorklogQueryService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 100;
    private static final Duration ISSUE_CACHE_TTL = Duration.ofMinutes(30);

    private final CodingOpenApiPort coding;
    private final CodingIssueLinkBuilder issueLinkBuilder;
    private final Clock clock;
    private final ExecutorService memberExecutor;
    private final boolean ownsExecutor;
    private final Map<IssueKey, CachedIssue> issueCache = new ConcurrentHashMap<>();

    @Autowired
    WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder,
                        com.itwray.iw.external.zhaogang.ZhaogangProperties properties) {
        this(coding, issueLinkBuilder, Clock.system(CHINA_ZONE),
                Executors.newFixedThreadPool(Math.max(1, properties.getWorklogExecutorConcurrency()),
                        daemonThreadFactory()), true);
    }

    WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder, Clock clock) {
        this(coding, issueLinkBuilder, clock, ForkJoinPool.commonPool(), false);
    }

    WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder, Clock clock,
                        ExecutorService memberExecutor) {
        this(coding, issueLinkBuilder, clock, memberExecutor, false);
    }

    private WorklogQueryService(CodingOpenApiPort coding, CodingIssueLinkBuilder issueLinkBuilder, Clock clock,
                                ExecutorService memberExecutor, boolean ownsExecutor) {
        this.coding = coding;
        this.issueLinkBuilder = issueLinkBuilder;
        this.clock = clock;
        this.memberExecutor = memberExecutor;
        this.ownsExecutor = ownsExecutor;
    }

    @PreDestroy
    void shutdown() {
        if (ownsExecutor) memberExecutor.shutdownNow();
    }

    Statistics queryStatistics(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                               List<WorklogModule.MemberCredential> requestedMembers, YearMonth month,
                               Schedule schedule) {
        return queryStatistics(context, team, scope, workbenchTeamId, requestedMembers, month, schedule, Map.of());
    }

    Statistics queryStatistics(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                               List<WorklogModule.MemberCredential> requestedMembers, YearMonth month,
                               Schedule schedule, Map<Long, Set<LocalDate>> leaveDatesByMember) {
        List<WorklogModule.MemberCredential> members = normalizeMembers(requestedMembers);
        if (members.isEmpty() && scope == Scope.WORKBENCH_TEAM) {
            return emptyStatistics(scope, workbenchTeamId, month, schedule);
        }
        LocalDate from = month.atDay(1);
        LocalDate toExclusive = month.plusMonths(1).atDay(1);
        List<MemberLogs> memberResults = fetchMembers(members, from, toExclusive);
        List<Worklog> allItems = deduplicate(flatten(memberResults), from, toExclusive);
        Map<Long, MemberLogs> resultsByMember = resultsByMember(memberResults);
        List<MemberDailyTotal> memberDailyTotals = scope == Scope.WORKBENCH_TEAM
                ? members.stream().filter(member -> !resultsByMember.get(member.userId()).failed())
                .map(member -> {
                    List<DailyTotal> totals = dailyTotals(
                            deduplicate(resultsByMember.get(member.userId()).items(), from, toExclusive),
                            from, toExclusive, schedule);
                    return new MemberDailyTotal(new User(member.userId(), member.userName(), member.avatar()),
                            totals, summary(totals, leaveDatesByMember.getOrDefault(member.userId(), Set.of())));
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
                        leaveDatesByMember.getOrDefault(member.userId(), Set.of()), from, toExclusive))
                .sum();
        Summary aggregateSummary = scope == Scope.WORKBENCH_TEAM
                ? aggregateMemberSummaries(memberDailyTotals, dailyTotals, aggregateWorkdayCount)
                : summary(dailyTotals, aggregateWorkdayCount);
        return new Statistics(coverage, month.toString(), schedule.versionNo(), syncedAt(),
                aggregateSummary,
                dailyTotals, memberDailyTotals);
    }

    Entries queryEntries(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                         List<WorklogModule.MemberCredential> requestedMembers, LocalDate from,
                         LocalDate toExclusive) {
        List<WorklogModule.MemberCredential> members = normalizeMembers(requestedMembers);
        if (members.isEmpty() && scope == Scope.WORKBENCH_TEAM) {
            return emptyEntries(scope, workbenchTeamId, from, toExclusive);
        }
        List<MemberLogs> memberResults = fetchMembers(members, from, toExclusive);
        List<Worklog> allItems = deduplicate(flatten(memberResults), from, toExclusive);
        List<Worklog> sortedItems = allItems.stream()
                .sorted(Comparator.comparingLong(this::sortStart).reversed()
                        .thenComparing(Comparator.comparingLong(this::sortSecondary).reversed())
                        .thenComparing(Comparator.comparingLong(Worklog::id).reversed()))
                .toList();
        Map<Long, String> memberTokens = memberResults.stream()
                .filter(result -> !result.failed() && StringUtils.isNotBlank(result.token()))
                .collect(LinkedHashMap::new, (map, result) -> map.put(result.userId(), result.token()),
                        LinkedHashMap::putAll);
        Map<IssueKey, CodingOpenApiPort.Issue> issues = loadIssues(memberTokens, team.host(), sortedItems);
        Map<Long, WorklogModule.MemberCredential> memberMap = members.stream().collect(LinkedHashMap::new,
                (map, member) -> map.put(member.userId(), member), LinkedHashMap::putAll);
        List<Item> entries = sortedItems.stream().map(item -> toItem(context, team, memberMap, issues, item)).toList();
        BigDecimal totalHours = entries.stream().map(Item::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        Coverage coverage = coverage(scope, workbenchTeamId, members, memberResults, projectCount(allItems));
        return new Entries(coverage, from.toString(), toExclusive.toString(), syncedAt(), normalizeHours(totalHours), entries);
    }

    Absence queryAbsences(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                          List<WorklogModule.MemberCredential> requestedMembers, YearMonth month,
                          Schedule schedule) {
        return queryAbsences(context, team, scope, workbenchTeamId, requestedMembers, month, schedule, Map.of());
    }

    Absence queryAbsences(WorklogModule.Context context, Team team, Scope scope, Long workbenchTeamId,
                          List<WorklogModule.MemberCredential> requestedMembers, YearMonth month,
                          Schedule schedule, Map<Long, Set<LocalDate>> leaveDatesByMember) {
        List<WorklogModule.MemberCredential> members = normalizeMembers(requestedMembers);
        LocalDate from = month.atDay(1);
        LocalDate monthEndExclusive = month.plusMonths(1).atDay(1);
        LocalDate today = LocalDate.now(clock);
        LocalDate toExclusive = today.isBefore(monthEndExclusive) ? today : monthEndExclusive;
        if (toExclusive.isBefore(from)) toExclusive = from;
        final LocalDate rangeEnd = toExclusive;
        if (!rangeEnd.isAfter(from) || (members.isEmpty() && scope == Scope.WORKBENCH_TEAM)) {
            return emptyAbsence(scope, workbenchTeamId, month, from, rangeEnd);
        }
        // CODING 按提交时间筛选工时列表，需扩大拉取窗口后再按实际工时日期截断。
        LocalDate fetchToExclusive = monthEndExclusive.isAfter(today.plusDays(1))
                ? monthEndExclusive : today.plusDays(1);
        List<MemberLogs> memberResults = fetchMembers(members, from, fetchToExclusive);
        Map<Long, MemberLogs> resultsByMember = resultsByMember(memberResults);
        Map<Long, WorklogModule.MemberCredential> memberMap = members.stream().collect(LinkedHashMap::new,
                (map, member) -> map.put(member.userId(), member), LinkedHashMap::putAll);
        List<MemberAbsence> absentMembers = members.stream()
                .map(member -> resultsByMember.get(member.userId()))
                .filter(result -> result != null && !result.failed() && !result.partial())
                .map(result -> {
                    WorklogModule.MemberCredential member = memberMap.get(result.userId());
                    List<AbsenceDay> days = absenceDays(deduplicate(result.items(), from, rangeEnd), from,
                            rangeEnd, schedule, leaveDatesByMember.getOrDefault(result.userId(), Set.of()));
                    return days.isEmpty() ? null : new MemberAbsence(
                            new User(member.userId(), member.userName(), member.avatar()), days.size(), days);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        Coverage coverage = coverage(scope, workbenchTeamId, members, memberResults, projectCount(flatten(memberResults)));
        return new Absence(coverage, month.toString(), from.toString(), rangeEnd.toString(), syncedAt(), absentMembers);
    }

    private List<WorklogModule.MemberCredential> normalizeMembers(List<WorklogModule.MemberCredential> requestedMembers) {
        return (requestedMembers == null ? List.<WorklogModule.MemberCredential>of() : requestedMembers).stream()
                .filter(member -> member.userId() > 0)
                .collect(LinkedHashMap<Long, WorklogModule.MemberCredential>::new,
                        (map, member) -> map.putIfAbsent(member.userId(), member), LinkedHashMap::putAll)
                .values().stream().toList();
    }

    private List<MemberLogs> fetchMembers(List<WorklogModule.MemberCredential> members, LocalDate from,
                                          LocalDate toExclusive) {
        long requestFrom = from.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli() - 1;
        long requestTo = toExclusive.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli() + 1;
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

    private List<Worklog> flatten(List<MemberLogs> results) {
        return results.stream().flatMap(result -> result.items().stream()).toList();
    }

    private Map<Long, MemberLogs> resultsByMember(List<MemberLogs> results) {
        return results.stream().collect(LinkedHashMap::new,
                (map, result) -> map.put(result.userId(), result), LinkedHashMap::putAll);
    }

    private Coverage coverage(Scope scope, Long workbenchTeamId, List<WorklogModule.MemberCredential> members,
                              List<MemberLogs> results, int projectCount) {
        int failedMembers = (int) results.stream().filter(MemberLogs::failed).count();
        boolean partial = failedMembers > 0 || results.stream().anyMatch(MemberLogs::partial);
        String permissionWarning = results.stream().map(MemberLogs::error)
                .filter(CodingOpenApiException.class::isInstance).map(CodingOpenApiException.class::cast)
                .filter(CodingOpenApiException::isPermissionDenied)
                .map(CodingOpenApiException::permissionMessage).findFirst().orElse("");
        String warning = StringUtils.isNotBlank(permissionWarning) ? permissionWarning : "";
        return new Coverage(scope, workbenchTeamId, members.size(), projectCount, partial, failedMembers, warning);
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

    private Absence emptyAbsence(Scope scope, Long workbenchTeamId, YearMonth month, LocalDate from,
                                 LocalDate toExclusive) {
        return new Absence(new Coverage(scope, workbenchTeamId, 0, 0, false, 0, ""), month.toString(),
                from.toString(), toExclusive.toString(), syncedAt(), List.of());
    }

    private String syncedAt() {
        return ZonedDateTime.now(clock).withZoneSameInstant(CHINA_ZONE)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private List<Worklog> deduplicate(List<Worklog> items, LocalDate from, LocalDate toExclusive) {
        Map<String, Worklog> deduplicated = new LinkedHashMap<>();
        items.stream().filter(item -> effectiveMillis(item) != null)
                .filter(item -> within(item, from, toExclusive))
                .forEach(item -> deduplicated.putIfAbsent(worklogKey(item), item));
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
                                         Schedule schedule) {
        return absenceDays(items, from, toExclusive, schedule, Set.of());
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

    private Summary summary(List<DailyTotal> dailyTotals) {
        return summary(dailyTotals, Set.of());
    }

    private Summary summary(List<DailyTotal> dailyTotals, Set<LocalDate> leaveDates) {
        int workdayCount = (int) dailyTotals.stream()
                .filter(item -> item.dayType() == DayType.WORKDAY)
                .filter(item -> LocalDate.parse(item.date()).isBefore(LocalDate.now(clock)))
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
                                     LocalDate from, LocalDate toExclusive) {
        LocalDate today = LocalDate.now(clock);
        return (int) from.datesUntil(toExclusive)
                .filter(date -> date.isBefore(today))
                .filter(date -> !leaveDates.contains(date))
                .filter(date -> schedule.days().stream().anyMatch(day -> day.date().equals(date)
                        && day.dayType() == DayType.WORKDAY))
                .count();
    }

    private MemberLogs fetchMember(WorklogModule.MemberCredential member, long from, long to) {
        if (StringUtils.isBlank(member.token())) {
            return new MemberLogs(member.userId(), member.token(), List.of(), true, true,
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
            return new MemberLogs(member.userId(), member.token(), List.of(), true, true, e);
        }
    }

    private Map<IssueKey, CodingOpenApiPort.Issue> loadIssues(Map<Long, String> memberTokens, String teamHost,
                                                               List<Worklog> items) {
        Map<IssueKey, CompletableFuture<CodingOpenApiPort.Issue>> pending = new LinkedHashMap<>();
        for (Worklog item : items) {
            if (item.issueCode() <= 0 || StringUtils.isBlank(item.projectName())) continue;
            String token = memberTokens.get(item.userId());
            if (StringUtils.isBlank(token)) continue;
            IssueKey key = new IssueKey(teamHost, item.projectName(), item.issueCode(),
                    CodingCredentialService.fingerprint(token));
            CodingOpenApiPort.Issue cached = cachedIssue(key);
            if (cached != null) {
                pending.put(key, CompletableFuture.completedFuture(cached));
            } else {
                pending.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(
                        () -> fetchIssue(token, key), memberExecutor));
            }
        }
        Map<IssueKey, CodingOpenApiPort.Issue> result = new LinkedHashMap<>();
        pending.forEach((key, future) -> {
            CodingOpenApiPort.Issue issue = future.join();
            if (issue != null) {
                result.put(key, issue);
                issueCache.put(key, new CachedIssue(issue, Instant.now().plus(ISSUE_CACHE_TTL)));
            }
        });
        trimIssueCache();
        return result;
    }

    private CodingOpenApiPort.Issue fetchIssue(String token, IssueKey key) {
        try {
            return coding.issue(token, key.projectName(), key.issueCode());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CodingOpenApiPort.Issue cachedIssue(IssueKey key) {
        CachedIssue cached = issueCache.get(key);
        if (cached == null || cached.expiresAt().isBefore(Instant.now())) {
            issueCache.remove(key);
            return null;
        }
        return cached.issue();
    }

    private void trimIssueCache() {
        if (issueCache.size() <= 5000) return;
        Instant now = Instant.now();
        issueCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private Item toItem(WorklogModule.Context context, Team team,
                        Map<Long, WorklogModule.MemberCredential> members,
                        Map<IssueKey, CodingOpenApiPort.Issue> issues, Worklog item) {
        WorklogModule.MemberCredential member = members.get(item.userId());
        User user = member == null
                ? new User(item.userId(), item.userId() == context.userId() ? context.userName() : "成员 " + item.userId(),
                item.userId() == context.userId() ? context.avatar() : "")
                : new User(member.userId(), member.userName(), member.avatar());
        String token = member == null ? "" : member.token();
        IssueKey key = new IssueKey(team.host(), item.projectName(), item.issueCode(),
                StringUtils.isBlank(token) ? "" : CodingCredentialService.fingerprint(token));
        CodingOpenApiPort.Issue issue = issues.get(key);
        String title = issue == null ? "" : issue.title();
        if (StringUtils.isBlank(title)) title = StringUtils.defaultIfBlank(item.workingDesc(), "事项 #" + item.issueCode());
        String type = issue == null ? "" : issue.type();
        String typeName = issue == null ? "事项" : StringUtils.defaultIfBlank(issue.typeName(), issue.type());
        String displayName = issue == null ? item.projectName()
                : StringUtils.defaultIfBlank(issue.projectDisplayName(), item.projectName());
        return new Item(item.id(), user, formatTime(effectiveMillis(item)), formatTime(item.createdAt()),
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
        Long millis = effectiveMillis(item);
        return millis == null ? null : Instant.ofEpochMilli(millis).atZone(CHINA_ZONE).toLocalDate();
    }

    private Long effectiveMillis(Worklog item) {
        return item.startAt() != null ? item.startAt() : item.createdAt();
    }

    private long sortStart(Worklog item) {
        Long value = effectiveMillis(item);
        return value == null ? 0 : value;
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
                String.valueOf(effectiveMillis(item)), String.valueOf(item.createdAt()), String.valueOf(item.hours()));
    }

    private record MemberLogs(long userId, String token, List<Worklog> items, boolean partial, boolean failed,
                              RuntimeException error) {
    }

    private record IssueKey(String teamHost, String projectName, long issueCode, String tokenFingerprint) {
    }

    private record CachedIssue(CodingOpenApiPort.Issue issue, Instant expiresAt) {
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
