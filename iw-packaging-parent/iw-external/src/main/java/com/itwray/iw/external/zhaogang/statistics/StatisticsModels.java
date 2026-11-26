package com.itwray.iw.external.zhaogang.statistics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class StatisticsModels {
    private StatisticsModels() {}

    public enum RangeKind { THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_QUARTER, CUSTOM }
    public enum SortOrder { ASC, DESC }
    public enum Status { READY, PARTIAL, UNAVAILABLE, NOT_APPLICABLE }

    public enum Metric {
        UTILIZATION_RATE, TOTAL_HOURS, OVERTIME_HOURS, OVERTIME_DAYS,
        COMMIT_COUNT, CHANGED_LINES, ADDITIONS, DELETIONS;

        boolean code() {
            return ordinal() >= COMMIT_COUNT.ordinal();
        }
    }

    public enum Reason {
        TOKEN_MISSING("WORKLOG", "工时统计凭证不可用"),
        PERMISSION_DENIED("WORKLOG", "当前凭证无权读取工时"),
        NOT_SYNCED("WORKLOG", "工时镜像尚未初始化"),
        SYNC_FAILED("WORKLOG", "工时同步失败"),
        INCOMPLETE("WORKLOG", "部分登记缺少必要信息"),
        INITIALIZING("WORKLOG", "工时镜像正在补齐历史数据"),
        STALE("WORKLOG", "工时镜像尚未更新"),
        OUTSIDE_COVERAGE("WORKLOG", "工时镜像从 2026 年起采集"),
        NO_WORKDAYS("WORKLOG", "所选区间没有应出勤工作日"),
        CODE_OUTSIDE_COVERAGE("CODE", "区间超出代码镜像保留窗口"),
        CODE_SYNC_RUNNING("CODE", "团队代码正在同步，统计覆盖待确认"),
        CODE_COVERAGE("CODE", "部分团队的代码扫描或凭证覆盖尚未就绪"),
        CODE_REPOSITORY_PARTIAL("CODE", "部分仓库同步失败，代码榜按已同步数据统计"),
        CODE_IDENTITY("CODE", "部分提交作者尚未归属或提交记录存在冲突"),
        CODE_DIFF("CODE", "部分提交的代码行数同步未完成，提交次数可以单独统计"),
        CODE_NOT_APPLICABLE("CODE", "仅当前前端或后端开发角色参与代码统计");

        final String domain;
        final String message;

        Reason(String domain, String message) {
            this.domain = domain;
            this.message = message;
        }
    }

    public record Range(LocalDate from, LocalDate to) {}
    public record Population(int total, int participating, int excluded, int developers) {}
    public record Indicator(BigDecimal value, Status status, List<Reason> reasonCodes,
                            int sampleCount, int targetCount) {}
    public record Summary(Indicator dailyHours, Indicator overtimeDays, Indicator overtimeHours,
                          Indicator commitCount, Indicator changedLines) {}
    public record Issue(String domain, Reason reasonCode, String safeMessage, List<Metric> affectedMetrics) {}
    public record DataStatus(Instant worklogSyncedAt, Instant codeSyncedAt, int affectedMemberCount) {}
    public record Refresh(String state, boolean stale, String jobId, Instant generatedAt, String safeMessage) {}
    public record Coverage(int sampleCount, int targetCount) {}
    public record RankItem(String userId, String name, int rank, BigDecimal value, boolean self) {}
    public record Position(Integer rank, Integer rankDelta, String reason, String comparisonReason) {}
    public record Ranking(Metric metric, List<RankItem> items, Position myPosition, Coverage coverage,
                          boolean cutoffTied, boolean allZero) {}
    public record MemberRow(String userId, String name, String avatar, String role, boolean self,
                            Integer rank, Integer rankDelta, String comparisonReason,
                            Map<Metric, BigDecimal> values, List<Issue> issues) {}
    public record Page(int pageNumber, int pageSize, int total, Integer myPage) {}
    public record Members(List<MemberRow> items, Page page, List<String> roles) {}
    public record Report(Range range, Range comparisonRange, String snapshotId, boolean hasData, Refresh refresh,
                         Population population, Summary summary, Ranking worklogRanking, Ranking codeRanking,
                         Members members, DataStatus dataStatus) {}

    record DirectoryMember(long userId, String name, String avatar, String role, boolean included) {
        boolean developer() {
            return "FRONTEND".equals(role) || "BACKEND".equals(role);
        }
    }

    record Day(long userId, LocalDate date, BigDecimal hours, boolean workday, BigDecimal overtimeHours,
               int overtimeDays, long commits, long additions, long deletions,
               Reason worklogReason, Reason commitReason, Reason linesReason) {}

    record MemberMetrics(DirectoryMember member, Map<Metric, BigDecimal> values, List<Issue> issues) {}
    record Ranked(DirectoryMember member, Map<Metric, BigDecimal> values, List<Issue> issues,
                  Map<Metric, Integer> ranks, Map<Metric, Integer> deltas, Map<Metric, String> comparisonReasons) {}
    record Payload(Population population, Summary summary, List<Ranked> members, DataStatus dataStatus) {}
}
