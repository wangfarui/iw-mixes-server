package com.itwray.iw.external.zhaogang.statistics;

import com.itwray.iw.external.zhaogang.worklog.WorklogDayRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;

final class StatisticsCalculator {
    private static final List<Metric> WORKLOG = List.of(Metric.UTILIZATION_RATE, Metric.TOTAL_HOURS,
            Metric.OVERTIME_HOURS, Metric.OVERTIME_DAYS);
    private static final List<Metric> CODE = List.of(Metric.COMMIT_COUNT, Metric.CHANGED_LINES, Metric.ADDITIONS, Metric.DELETIONS);
    private static final List<Metric> LINES = CODE.subList(1, CODE.size());
    private static final int TOP = 10;

    private StatisticsCalculator() {}

    static Payload calculate(List<DirectoryMember> directory, List<Day> current, List<Day> previous, DataStatus times) {
        var included = directory.stream().filter(DirectoryMember::included).toList();
        int developers = (int) included.stream().filter(DirectoryMember::developer).count();
        Population population = new Population(directory.size(), included.size(), directory.size() - included.size(), developers);
        var totals = accumulate(current);
        var prior = previous == null ? Map.<Long, Totals>of() : accumulate(previous);
        var currentMembers = metrics(included, totals);
        var currentRanks = rank(currentMembers);
        var previousRanks = rank(metrics(included, prior));
        Set<Metric> comparable = EnumSet.noneOf(Metric.class);
        for (Metric metric : Metric.values()) {
            if (currentRanks.get(metric).keySet().equals(previousRanks.get(metric).keySet())) comparable.add(metric);
        }
        List<Ranked> ranked = currentMembers.stream().map(member -> {
            long id = member.member().userId();
            Map<Metric, Integer> ranks = new EnumMap<>(Metric.class);
            Map<Metric, Integer> deltas = new EnumMap<>(Metric.class);
            Map<Metric, String> reasons = new EnumMap<>(Metric.class);
            for (Metric metric : Metric.values()) {
                var now = currentRanks.get(metric);
                var before = previousRanks.get(metric);
                Integer rank = now.get(id);
                if (rank != null) ranks.put(metric, rank);
                if (previous == null) reasons.put(metric, "没有可比较的上期日期");
                else if (rank == null || !before.containsKey(id)) reasons.put(metric, "本期或上期缺少有效名次");
                else if (!comparable.contains(metric)) reasons.put(metric, "两期有效排名成员不一致");
                else deltas.put(metric, before.get(id) - rank);
            }
            return new Ranked(member.member(), member.values(), member.issues(), ranks, deltas, reasons);
        }).toList();

        boolean repositoriesPartial = current.stream().anyMatch(day -> day.commitReason() == Reason.CODE_REPOSITORY_PARTIAL);
        Summary summary = new Summary(dailyHours(currentMembers, totals, included.size()),
                average(currentMembers, Metric.OVERTIME_DAYS, included.size(), 1, false),
                average(currentMembers, Metric.OVERTIME_HOURS, included.size(), 2, false),
                average(currentMembers, Metric.COMMIT_COUNT, developers, 2, repositoriesPartial),
                average(currentMembers, Metric.CHANGED_LINES, developers, 0, repositoriesPartial));
        int affected = (int) ranked.stream().filter(member -> member.issues().stream().anyMatch(StatisticsCalculator::isProblem)).count();
        return new Payload(population, summary, ranked, new DataStatus(times.worklogSyncedAt(), times.codeSyncedAt(), affected));
    }

    static boolean isProblem(Issue issue) {
        return issue.reasonCode() != Reason.NO_WORKDAYS && issue.reasonCode() != Reason.CODE_NOT_APPLICABLE;
    }

    private static Map<Long, Totals> accumulate(List<Day> rows) {
        Map<Long, Totals> totals = new HashMap<>();
        for (Day day : rows) {
            totals.computeIfAbsent(day.userId(), ignored -> new Totals()).add(day);
        }
        return totals;
    }

    private static List<MemberMetrics> metrics(List<DirectoryMember> members, Map<Long, Totals> totals) {
        return members.stream().map(member -> {
            Totals total = totals.get(member.userId());
            Map<Metric, BigDecimal> values = new EnumMap<>(Metric.class);
            List<Issue> issues = new ArrayList<>();
            if (total == null) return new MemberMetrics(member, values, issues);
            boolean partialWorklog = total.worklogReasons.contains(Reason.WORKLOG_PROJECT_PARTIAL);
            if (total.worklogReasons.isEmpty() || (partialWorklog && total.hours.signum() > 0)) {
                values.put(Metric.TOTAL_HOURS, total.hours);
                values.put(Metric.OVERTIME_HOURS, total.overtime);
                values.put(Metric.OVERTIME_DAYS, BigDecimal.valueOf(total.overtimeDays));
                if (total.workdays > 0) values.put(Metric.UTILIZATION_RATE, WorklogDayRules.utilization(total.hours, total.workdays));
                else issues.add(issue(Reason.NO_WORKDAYS, List.of(Metric.UTILIZATION_RATE)));
            }
            total.worklogReasons.forEach(reason -> issues.add(issue(reason, WORKLOG)));

            if (!member.developer()) issues.add(issue(Reason.CODE_NOT_APPLICABLE, CODE));
            else if (!total.commitReasons.isEmpty()) total.commitReasons.forEach(reason -> issues.add(issue(reason, CODE)));
            else if (total.codeWarnings.isEmpty() || total.commits > 0) {
                values.put(Metric.COMMIT_COUNT, BigDecimal.valueOf(total.commits));
                if (total.lineReasons.isEmpty()) {
                    var added = BigDecimal.valueOf(total.additions);
                    var deleted = BigDecimal.valueOf(total.deletions);
                    values.put(Metric.ADDITIONS, added);
                    values.put(Metric.DELETIONS, deleted);
                    values.put(Metric.CHANGED_LINES, added.add(deleted));
                } else total.lineReasons.forEach(reason -> issues.add(issue(reason, LINES)));
            }
            total.codeWarnings.stream()
                    .filter(reason -> reason != Reason.CODE_REPOSITORY_PARTIAL || total.commits == 0)
                    .forEach(reason -> issues.add(issue(reason, CODE)));
            return new MemberMetrics(member, values, issues);
        }).toList();
    }

    private static Issue issue(Reason reason, List<Metric> affected) {
        return new Issue(reason.domain, reason, reason.message, affected);
    }

    private static Map<Metric, Map<Long, Integer>> rank(List<MemberMetrics> members) {
        Map<Metric, Map<Long, Integer>> result = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            var eligible = members.stream().filter(member -> member.values().containsKey(metric))
                    .sorted(Comparator.<MemberMetrics, BigDecimal>comparing(member -> member.values().get(metric)).reversed()
                            .thenComparingLong(member -> member.member().userId())).toList();
            Map<Long, Integer> ranks = new HashMap<>();
            BigDecimal last = null;
            int rank = 0;
            for (int i = 0; i < eligible.size(); i++) {
                var member = eligible.get(i);
                BigDecimal value = member.values().get(metric);
                if (last == null || last.compareTo(value) != 0) rank = i + 1;
                ranks.put(member.member().userId(), rank);
                last = value;
            }
            result.put(metric, ranks);
        }
        return result;
    }

    private static Indicator dailyHours(List<MemberMetrics> members, Map<Long, Totals> totals, int targets) {
        BigDecimal sum = BigDecimal.ZERO;
        long days = 0;
        int count = 0;
        for (MemberMetrics member : members) {
            if (!member.values().containsKey(Metric.UTILIZATION_RATE)) continue;
            Totals total = totals.get(member.member().userId());
            sum = sum.add(total.hours);
            days += total.workdays;
            count++;
        }
        return indicator(sum, days, count, targets, 2, reasons(members, Metric.UTILIZATION_RATE));
    }

    private static Indicator average(List<MemberMetrics> members, Metric metric, int targets, int scale,
                                     boolean repositoriesPartial) {
        var values = members.stream().map(member -> member.values().get(metric)).filter(Objects::nonNull).toList();
        Set<Reason> reasons = reasons(members, metric);
        if (repositoriesPartial) reasons.add(Reason.CODE_REPOSITORY_PARTIAL);
        return indicator(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add), values.size(), values.size(), targets,
                scale, reasons);
    }

    private static Set<Reason> reasons(List<MemberMetrics> rows, Metric metric) {
        return rows.stream().flatMap(member -> member.issues().stream()).filter(issue -> issue.affectedMetrics().contains(metric))
                .map(Issue::reasonCode).filter(reason -> reason != Reason.CODE_NOT_APPLICABLE)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Reason.class)));
    }

    private static Indicator indicator(BigDecimal sum, long denominator, int count, int targets, int scale, Set<Reason> reasons) {
        Status status;
        if (targets == 0 || (denominator == 0 && reasons.equals(Set.of(Reason.NO_WORKDAYS)))) status = Status.NOT_APPLICABLE;
        else if (denominator == 0) status = Status.UNAVAILABLE;
        else status = count < targets || reasons.stream().anyMatch(reason -> reason != Reason.NO_WORKDAYS)
                ? Status.PARTIAL : Status.READY;
        return new Indicator(denominator == 0 ? null : sum.divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP),
                status, List.copyOf(reasons), count, targets);
    }

    static Ranking ranking(Payload payload, long user, Metric metric) {
        var eligible = payload.members().stream().filter(row -> row.ranks().containsKey(metric))
                .sorted(Comparator.comparingInt((Ranked row) -> row.ranks().get(metric))
                        .thenComparingLong(row -> row.member().userId())).toList();
        Ranked self = payload.members().stream().filter(row -> row.member().userId() == user).findFirst().orElse(null);
        Integer selfRank = self == null ? null : self.ranks().get(metric);
        String reason = self == null ? "未纳入本期统计" : self.issues().stream()
                .filter(issue -> issue.affectedMetrics().contains(metric)).map(Issue::safeMessage)
                .findFirst().orElse("本期无有效名次");
        Position position = new Position(selfRank, self == null ? null : self.deltas().get(metric),
                selfRank == null ? reason : null, self == null ? null : self.comparisonReasons().get(metric));
        List<RankItem> items = eligible.stream().limit(TOP).map(row -> new RankItem(Long.toString(row.member().userId()),
                row.member().name(), row.ranks().get(metric), row.values().get(metric), user == row.member().userId())).toList();
        boolean tied = eligible.size() > TOP && eligible.get(TOP - 1).ranks().get(metric).equals(eligible.get(TOP).ranks().get(metric));
        boolean allZero = !eligible.isEmpty() && eligible.get(0).values().get(metric).signum() == 0;
        return new Ranking(metric, items, position, new Coverage(eligible.size(),
                metric.code() ? payload.population().developers() : payload.population().participating()), tied, allZero);
    }

    static Members members(Payload payload, long user, Metric metric, SortOrder order,
                           String keyword, String role, int page, int size) {
        Comparator<BigDecimal> values = order == SortOrder.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder();
        var filtered = payload.members().stream().filter(row -> role == null || role.isBlank()
                        || role.equals(row.member().role()) || ("UNSET".equals(role) && row.member().role() == null))
                .filter(row -> row.member().name().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                .sorted(Comparator.comparing((Ranked row) -> row.values().get(metric), Comparator.nullsLast(values))
                        .thenComparingLong(row -> row.member().userId())).toList();
        int number = Math.min(page, Math.max(1, (filtered.size() + size - 1) / size));
        Integer myPage = null;
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).member().userId() == user) {
                myPage = i / size + 1;
                break;
            }
        }
        var rows = filtered.stream().skip((long) (number - 1) * size).limit(size).map(row ->
                new MemberRow(Long.toString(row.member().userId()), row.member().name(), row.member().avatar(),
                        row.member().role(), user == row.member().userId(), row.ranks().get(metric), row.deltas().get(metric),
                        row.comparisonReasons().get(metric), row.values(), row.issues())).toList();
        var roles = payload.members().stream().map(row -> row.member().role() == null ? "UNSET" : row.member().role())
                .distinct().sorted().toList();
        return new Members(rows, new Page(number, size, filtered.size(), myPage), roles);
    }

    private static final class Totals {
        BigDecimal hours = BigDecimal.ZERO;
        BigDecimal overtime = BigDecimal.ZERO;
        int workdays;
        int overtimeDays;
        long commits;
        long additions;
        long deletions;
        Set<Reason> worklogReasons = EnumSet.noneOf(Reason.class);
        Set<Reason> commitReasons = EnumSet.noneOf(Reason.class);
        Set<Reason> codeWarnings = EnumSet.noneOf(Reason.class);
        Set<Reason> lineReasons = EnumSet.noneOf(Reason.class);

        void add(Day day) {
            hours = hours.add(day.hours());
            overtime = overtime.add(day.overtimeHours());
            workdays += day.workday() ? 1 : 0;
            overtimeDays += day.overtimeDays();
            commits += day.commits();
            additions += day.additions();
            deletions += day.deletions();
            if (day.worklogReason() != null) worklogReasons.add(day.worklogReason());
            if (day.commitReason() == Reason.CODE_REPOSITORY_PARTIAL || day.commitReason() == Reason.CODE_AUTHOR_PARTIAL) {
                codeWarnings.add(day.commitReason());
            } else if (day.commitReason() != null) {
                commitReasons.add(day.commitReason());
            }
            if (day.linesReason() != null) lineReasons.add(day.linesReason());
        }
    }
}
