package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.external.zhaogang.calendar.PersonalLeaveResolver;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarResolver;
import com.itwray.iw.external.zhaogang.codestats.CodeStatisticsSource;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import com.itwray.iw.external.zhaogang.worklog.WorklogDayRules;
import com.itwray.iw.external.zhaogang.worklog.WorklogStatisticsSource;
import com.itwray.iw.external.zhaogang.worklog.WorklogStatisticsSource.Selection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType.WORKDAY;
import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;
import static com.itwray.iw.external.zhaogang.statistics.StatisticsRepository.*;

@Component
class StatisticsBuilder {
    private record BuiltMonth(Monthly metadata, List<Day> rows) {}
    private record BuiltReport(Revision revision, Payload payload) {}

    private final StatisticsRepository repository;
    private final OrganizationStatisticsDirectory directory;
    private final WorklogStatisticsSource worklogs;
    private final CodeStatisticsSource code;
    private final WorkCalendarResolver calendar;
    private final PersonalLeaveResolver leaves;

    StatisticsBuilder(StatisticsRepository repository, OrganizationStatisticsDirectory directory, WorklogStatisticsSource worklogs,
                      CodeStatisticsSource code, WorkCalendarResolver calendar, PersonalLeaveResolver leaves) {
        this.repository = repository;
        this.directory = directory;
        this.worklogs = worklogs;
        this.code = code;
        this.calendar = calendar;
        this.leaves = leaves;
    }

    boolean build(Task task, LocalDate today, LocalDate retention, String policy, Clock clock) {
        var period = StatisticsPeriod.fromKey(task.key());
        var months = period.months();
        // Generate the current period before comparison/older history. Each month can be
        // reused by other ranges, including long custom queries and restarted jobs.
        for (int i = months.size() - 1; i >= 0; i--) {
            YearMonth month = months.get(i);
            if (!repository.heartbeat(task, clock.millis())) return false;
            BuiltMonth built = repository.read(() -> {
                Revision revision = repository.revision(task.org(), List.of(month), false);
                Monthly existing = repository.month(task.org(), month);
                String version = revision.monthVersion(month, policy);
                if (existing != null && existing.sourceVersion().equals(version) && existing.validUntil() > clock.millis()) return null;
                return month(task.org(), month, today, retention, revision, version);
            });
            if (built != null && !repository.publishMonth(task, month, built.metadata, policy, built.rows, clock::millis)) return false;
        }
        BuiltReport report = repository.read(() -> {
            Revision revision = repository.revision(task.org(), months, false);
            long now = clock.millis();
            Long worklogSync = null;
            Long codeSync = null;
            for (YearMonth month : months) {
                Monthly stored = repository.month(task.org(), month);
                if (stored == null || !stored.sourceVersion().equals(revision.monthVersion(month, policy)) || stored.validUntil() <= now) {
                    return null;
                }
                if (!month.atEndOfMonth().isBefore(period.range().from()) && !month.atDay(1).isAfter(period.range().to())) {
                    worklogSync = max(worklogSync, stored.worklogSyncedAt());
                    codeSync = max(codeSync, stored.codeSyncedAt());
                }
            }
            var members = members(directory.memberships(task.org()));
            List<Day> current = repository.days(task.org(), period.range());
            List<Day> previous = period.comparison() == null ? null : repository.days(task.org(), period.comparison());
            return new BuiltReport(revision, StatisticsCalculator.calculate(members, current, previous,
                    new DataStatus(instant(worklogSync), instant(codeSync), 0)));
        });
        return report != null && repository.publishReport(task, report.revision,
                report.revision.fingerprint(months, policy), policy, months, report.payload, clock::millis);
    }

    private BuiltMonth month(long org, YearMonth month, LocalDate today, LocalDate retention, Revision revision, String version) {
        var memberships = directory.memberships(org);
        var members = members(memberships).stream().filter(DirectoryMember::included).toList();
        if (members.isEmpty()) return new BuiltMonth(new Monthly(version, Long.MAX_VALUE, null, null), List.of());

        Map<Long, Selection> selections = new HashMap<>();
        for (Membership relation : memberships) {
            if (relation.ignored() || selections.containsKey(relation.userId())) continue;
            if (relation.personalFingerprint() != null) {
                selections.put(relation.userId(), new Selection(0, relation.personalFingerprint()));
            } else if (relation.managementFingerprint() != null) {
                selections.put(relation.userId(), new Selection(relation.teamId(), relation.managementFingerprint()));
            }
        }
        members.forEach(member -> selections.putIfAbsent(member.userId(), null));
        var work = worklogs.read(org, month, selections);
        var commits = code.read(org, month, today, retention, memberships);
        var schedule = calendar.resolve(org, month);
        var leaveDates = leaves.resolve(org, members.stream().map(DirectoryMember::userId).toList(), month);
        List<Day> days = new ArrayList<>(members.size() * month.lengthOfMonth());
        for (DirectoryMember member : members) {
            long user = member.userId();
            var hoursByDate = work.hours().getOrDefault(user, Map.of());
            var leave = leaveDates.getOrDefault(user, Set.of());
            var worklogReason = reason(work.reasons().get(user));
            if (worklogReason != null && !hoursByDate.isEmpty()) worklogReason = Reason.WORKLOG_PROJECT_PARTIAL;
            for (var day : schedule.days()) {
                LocalDate date = day.date();
                BigDecimal hours = hoursByDate.getOrDefault(date, BigDecimal.ZERO);
                var overtime = WorklogDayRules.overtime(hours, day.dayType());
                boolean eligible = day.dayType() == WORKDAY && !leave.contains(date);
                var contribution = commits.data().get(new CodeStatisticsSource.Key(user, date));
                Reason commitReason = reason(commits.reason());
                if (!member.developer()) commitReason = Reason.CODE_NOT_APPLICABLE;
                else if (commitReason == null && commits.unmappedMembers().contains(user)) commitReason = Reason.CODE_IDENTITY;
                Reason linesReason = commitReason != null ? commitReason
                        : contribution != null && contribution.linesIncomplete() ? Reason.CODE_DIFF : null;
                // Persist the coverage warning with the monthly facts so reused snapshots carry it too.
                if (commitReason == null && commits.ambiguousDates().contains(date)) commitReason = Reason.CODE_AUTHOR_PARTIAL;
                else if (commitReason == null && commits.repositoriesPartial()) commitReason = Reason.CODE_REPOSITORY_PARTIAL;
                days.add(new Day(user, date, hours, eligible, overtime.hours(), overtime.days(),
                        contribution == null ? 0 : contribution.commits(), contribution == null ? 0 : contribution.additions(),
                        contribution == null ? 0 : contribution.deletions(), worklogReason, commitReason, linesReason));
            }
        }
        return new BuiltMonth(new Monthly(version, work.validUntil(), work.syncedAt(), commits.syncedAt()), days);
    }

    private static List<DirectoryMember> members(List<Membership> memberships) {
        Map<Long, DirectoryMember> members = new LinkedHashMap<>();
        for (Membership relation : memberships) {
            var existing = members.get(relation.userId());
            if (existing == null || (!existing.included() && !relation.ignored())) {
                members.put(relation.userId(), new DirectoryMember(relation.userId(), relation.name(),
                        relation.avatar(), relation.role(), !relation.ignored()));
            }
        }
        return new ArrayList<>(members.values());
    }

    private static Reason reason(String code) {
        return code == null ? null : Reason.valueOf(code);
    }

    private static Instant instant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    private static Long max(Long left, Long right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.max(left, right);
    }
}
