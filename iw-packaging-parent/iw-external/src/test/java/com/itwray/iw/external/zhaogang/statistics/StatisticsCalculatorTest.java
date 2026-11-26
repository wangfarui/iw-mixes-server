package com.itwray.iw.external.zhaogang.statistics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class StatisticsCalculatorTest {
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 18);
    private static final DataStatus UNSYNCED = new DataStatus(null, null, 0);

    @Test
    void weightsWorkdaysAndRetainsZeroAndMetricSpecificCoverage() {
        var roster = List.of(member(1, "BACKEND"), member(2, "FRONTEND"), member(3, "QA"),
                new DirectoryMember(4, "排除人员", null, "BACKEND", false));
        var rows = List.of(
                day(1, WEDNESDAY, 10, true, 2, 1, 1, 16, 4, null, null),
                day(1, WEDNESDAY.plusDays(1), 8, true, 0, 0, 0, 0, 0, null, null),
                day(2, WEDNESDAY, 0, true, 0, 0, 0, 0, 0, null, null),
                day(2, WEDNESDAY.plusDays(1), 7, false, 0, 0, 1, 0, 0, null, Reason.CODE_DIFF),
                day(3, WEDNESDAY, 4, false, 4, 1, 0, 0, 0, null, null));
        var payload = StatisticsCalculator.calculate(roster, rows, null, UNSYNCED);
        assertThat(payload.population()).isEqualTo(new Population(4, 3, 1, 2));
        assertThat(payload.summary().dailyHours().value()).isEqualByComparingTo("8.33");
        assertThat(payload.summary().dailyHours().sampleCount()).isEqualTo(2);
        assertThat(payload.summary().overtimeHours().value()).isEqualByComparingTo("2.00");
        assertThat(payload.summary().commitCount().value()).isEqualByComparingTo("1.00");
        assertThat(payload.summary().changedLines().value()).isEqualByComparingTo("20");
        assertThat(payload.summary().changedLines().status()).isEqualTo(Status.PARTIAL);
        assertThat(payload.members().get(2).values()).doesNotContainKey(Metric.UTILIZATION_RATE);
        assertThat(payload.dataStatus().affectedMemberCount()).isEqualTo(1);
    }

    @Test
    void globalTiesAndPreviousMembershipSurviveFilteringAndPagination() {
        var roster = IntStream.rangeClosed(1, 12).mapToObj(id -> member(id, "BACKEND")).toList();
        var current = new ArrayList<Day>();
        var previous = new ArrayList<Day>();
        for (int id = 1; id <= 12; id++) {
            int hours = id >= 10 ? 0 : 10;
            current.add(day(id, WEDNESDAY, hours, true, 0, 0, 0, 0, 0, null, null));
            previous.add(day(id, WEDNESDAY.minusWeeks(1), id == 12 ? 20 : hours, true, 0, 0, 0, 0, 0,
                    id == 5 ? Reason.NOT_SYNCED : null, null));
        }
        var payload = StatisticsCalculator.calculate(roster, current, previous, UNSYNCED);
        var rank = StatisticsCalculator.ranking(payload, 12, Metric.TOTAL_HOURS);
        assertThat(rank.items()).hasSize(10);
        assertThat(rank.cutoffTied()).isTrue();
        assertThat(rank.myPosition().rank()).isEqualTo(10);
        assertThat(rank.myPosition().rankDelta()).isNull();
        assertThat(rank.myPosition().comparisonReason()).contains("不一致");
        var table = StatisticsCalculator.members(payload, 12, Metric.TOTAL_HOURS, SortOrder.DESC, "人员12", null, 1, 10);
        assertThat(table.items()).extracting(MemberRow::rank).containsExactly(10);
        assertThat(table.page().myPage()).isEqualTo(1);
    }

    @Test
    void repositoryFailureWarnsWithoutRemovingCodeValuesOrReportingMemberErrors() {
        var roster = List.of(member(1, "BACKEND"), member(2, "FRONTEND"));
        var rows = List.of(
                new Day(1, WEDNESDAY, BigDecimal.TEN, true, BigDecimal.ZERO, 0, 2, 14, 6,
                        null, Reason.CODE_REPOSITORY_PARTIAL, null),
                new Day(2, WEDNESDAY, BigDecimal.TEN, true, BigDecimal.ZERO, 0, 1, 0, 0,
                        null, Reason.CODE_REPOSITORY_PARTIAL, Reason.CODE_DIFF));
        var payload = StatisticsCalculator.calculate(roster, rows, null, UNSYNCED);
        assertThat(payload.summary().commitCount().status()).isEqualTo(Status.PARTIAL);
        assertThat(payload.summary().commitCount().value()).isEqualByComparingTo("1.5");
        assertThat(payload.summary().changedLines().value()).isEqualByComparingTo("20");
        assertThat(payload.summary().changedLines().reasonCodes()).containsExactlyInAnyOrder(
                Reason.CODE_REPOSITORY_PARTIAL, Reason.CODE_DIFF);
        assertThat(StatisticsCalculator.ranking(payload, 1, Metric.COMMIT_COUNT).items())
                .extracting(RankItem::userId).containsExactly("1", "2");
        assertThat(StatisticsCalculator.ranking(payload, 1, Metric.CHANGED_LINES).items())
                .extracting(RankItem::userId).containsExactly("1");
        assertThat(payload.dataStatus().affectedMemberCount()).isEqualTo(1);
    }

    private DirectoryMember member(long id, String role) {
        return new DirectoryMember(id, "人员" + id, null, role, true);
    }

    private Day day(long id, LocalDate date, int hours, boolean workday, int overtimeHours, int overtimeDays,
                    long commits, long added, long deleted, Reason worklog, Reason lines) {
        return new Day(id, date, BigDecimal.valueOf(hours), workday, BigDecimal.valueOf(overtimeHours),
                overtimeDays, commits, added, deleted, worklog, null, lines);
    }
}
