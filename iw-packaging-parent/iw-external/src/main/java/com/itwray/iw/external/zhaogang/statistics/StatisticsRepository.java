package com.itwray.iw.external.zhaogang.statistics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;

@Repository
class StatisticsRepository {
    static final long LEASE_MS = 120_000;
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CUSTOM_RANGES = 100;
    private static final long CODE_SYNC_RETRY_MS = 30_000;

    record Revision(long global, long visibility, long codeVisibilityMs, Map<YearMonth, Long> months) {
        String monthVersion(YearMonth month, String policy) {
            return global + ":" + months.getOrDefault(month, 0L) + ":" + policy;
        }

        String fingerprint(List<YearMonth> periods, String policy) {
            return CodingCredentialService.fingerprint(global + ":" + policy + ":" + periods.stream()
                    .map(month -> month + "=" + months.getOrDefault(month, 0L)).toList());
        }
    }

    record Monthly(String sourceVersion, long validUntil, Long worklogSyncedAt, Long codeSyncedAt) {}
    record Stored(String snapshotId, long visibility, String policy, String sourceVersion, long generatedAt, String payload) {}
    record Task(long org, String key, String jobId, String state, int attempts, int priority, String lease,
                long leaseUntil, long nextRetry, long requestedAt, long completedAt, long visibility,
                String sourceVersion, String safeError) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    StatisticsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.write.setTimeout(60);
        this.read.setReadOnly(true);
        this.read.setTimeout(90);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    <T> T read(Supplier<T> work) {
        return read.execute(status -> work.get());
    }

    boolean authorized(long org, long user, String fingerprint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM external_zhaogang_coding_credential
                  WHERE coding_team_id=:org AND coding_user_id=:user AND token_fingerprint=:fingerprint
                    AND token_plaintext<>'')
                """, Map.of("org", org, "user", user, "fingerprint", fingerprint), Boolean.class));
    }

    List<Long> organizations() {
        return jdbc.queryForList("SELECT DISTINCT coding_team_id FROM external_zhaogang_workbench_team ORDER BY coding_team_id",
                Map.of(), Long.class);
    }

    Revision revision(long org, List<YearMonth> months, boolean lock) {
        List<String> keys = new ArrayList<>();
        keys.add("*");
        months.stream().map(YearMonth::toString).forEach(keys::add);
        return jdbc.query("""
                SELECT stat_month, version_no, visibility_version, code_visibility_ms
                FROM external_zhaogang_statistics_revision
                WHERE coding_team_id=:org AND stat_month IN (:keys)
                ORDER BY stat_month
                """ + (lock ? " FOR UPDATE" : ""), Map.of("org", org, "keys", keys), rs -> {
            Map<YearMonth, Long> versions = new HashMap<>();
            long global = 0;
            long visibility = 0;
            long codeVisibility = 0;
            while (rs.next()) {
                String key = rs.getString("stat_month");
                if (key.equals("*")) {
                    global = rs.getLong("version_no");
                    visibility = rs.getLong("visibility_version");
                    codeVisibility = rs.getLong("code_visibility_ms");
                } else {
                    versions.put(YearMonth.parse(key), rs.getLong("version_no"));
                }
            }
            return new Revision(global, visibility, codeVisibility, versions);
        });
    }

    Stored stored(long org, String key, boolean payload) {
        return jdbc.query("""
                SELECT snapshot_id, visibility_version, policy_key, source_version, generated_at,
                """ + (payload ? "payload" : "NULL") + """
                 FROM external_zhaogang_statistics_report WHERE coding_team_id=:org AND range_key=:key
                """, key(org, key), (rs, n) -> new Stored(rs.getString(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)))
                .stream().findFirst().orElse(null);
    }

    Task task(long org, String key, boolean lock) {
        return jdbc.query("SELECT * FROM external_zhaogang_statistics_refresh_task WHERE coding_team_id=:org AND range_key=:key"
                        + (lock ? " FOR UPDATE" : ""), key(org, key), this::mapTask).stream().findFirst().orElse(null);
    }

    Task enqueue(long org, String key, long visibility, String sourceVersion, int priority, long now,
                 long observedAt, boolean manual) {
        return write.execute(status -> {
            jdbc.update("""
                    INSERT INTO external_zhaogang_statistics_revision(coding_team_id,stat_month)
                    VALUES (:org, '*') ON DUPLICATE KEY UPDATE version_no=version_no
                    """, Map.of("org", org));
            // Serialize quota and deduplication among creators. Active tasks never lose their lease to a read.
            long currentVisibility = jdbc.queryForObject("""
                    SELECT visibility_version FROM external_zhaogang_statistics_revision
                    WHERE coding_team_id=:org AND stat_month='*' FOR UPDATE
                    """, Map.of("org", org), Long.class);
            if (currentVisibility != visibility) return task(org, key, false);
            Task existing = task(org, key, true);
            if (existing == null) {
                if (priority > 0 && jdbc.queryForObject("""
                        SELECT COUNT(*) FROM external_zhaogang_statistics_refresh_task
                        WHERE coding_team_id=:org AND priority_no>0
                        """, Map.of("org", org), Integer.class) >= MAX_CUSTOM_RANGES) {
                    // Only make room by evicting a terminal custom range.
                    String obsolete = jdbc.queryForList("""
                            SELECT range_key FROM external_zhaogang_statistics_refresh_task
                            WHERE coding_team_id=:org AND priority_no>0
                              AND (state='IDLE' OR (state='FAILED' AND attempt>=:max))
                            ORDER BY requested_at, range_key LIMIT 1
                            """, Map.of("org", org, "max", MAX_ATTEMPTS), String.class).stream().findFirst().orElse(null);
                    if (obsolete == null) return null;
                    deleteTerminal(org, obsolete, now);
                }
                jdbc.update("""
                        INSERT INTO external_zhaogang_statistics_refresh_task
                          (coding_team_id,range_key,job_id,state,priority_no,next_retry_at,requested_at,requested_visibility,requested_source_version)
                        VALUES (:org,:key,:job,'QUEUED',:priority,:now,:now,:visibility,:source)
                        """, Map.of("org", org, "key", key, "job", UUID.randomUUID().toString(),
                        "priority", priority, "now", now, "visibility", visibility, "source", sourceVersion));
            } else if (("IDLE".equals(existing.state) && existing.completedAt <= observedAt)
                    || ("FAILED".equals(existing.state) && (manual || existing.visibility < visibility
                    || !sourceVersion.equals(existing.sourceVersion)))) {
                jdbc.update("""
                        UPDATE external_zhaogang_statistics_refresh_task
                        SET job_id=:job,state='QUEUED',attempt=0,priority_no=LEAST(priority_no,:priority),
                            next_retry_at=:now,requested_at=:now,requested_visibility=:visibility,requested_source_version=:source,
                            safe_error=NULL
                        WHERE coding_team_id=:org AND range_key=:key
                        """, Map.of("org", org, "key", key, "job", UUID.randomUUID().toString(),
                        "priority", priority, "now", now, "visibility", visibility, "source", sourceVersion));
            }
            return task(org, key, false);
        });
    }

    List<Task> pending(long now) {
        return jdbc.query("""
                SELECT * FROM external_zhaogang_statistics_refresh_task
                WHERE (state='QUEUED' AND next_retry_at<=:now) OR (state='RUNNING' AND lease_until<=:now)
                   OR (state='FAILED' AND attempt<:max AND next_retry_at<=:now)
                ORDER BY priority_no, requested_at, coding_team_id LIMIT 8
                """, Map.of("now", now, "max", MAX_ATTEMPTS), this::mapTask);
    }

    Task claim(Task task, long now) {
        String lease = UUID.randomUUID().toString();
        int changed = jdbc.update("""
                UPDATE external_zhaogang_statistics_refresh_task
                SET state='RUNNING', lease_id=:lease, lease_until=:until, attempt=attempt+1, safe_error=NULL
                WHERE coding_team_id=:org AND range_key=:key AND job_id=:job
                AND ((state='QUEUED' AND next_retry_at<=:now) OR (state='RUNNING' AND lease_until<=:now)
                   OR (state='FAILED' AND attempt<:max AND next_retry_at<=:now))
                """, Map.of("org", task.org, "key", task.key, "job", task.jobId, "lease", lease,
                "now", now, "until", now + LEASE_MS, "max", MAX_ATTEMPTS));
        return changed == 0 ? null : task(task.org, task.key, false);
    }

    boolean heartbeat(Task task, long now) {
        return jdbc.update("""
                UPDATE external_zhaogang_statistics_refresh_task SET lease_until=:until
                WHERE coding_team_id=:org AND range_key=:key AND state='RUNNING' AND lease_id=:lease AND lease_until>:now
                """, Map.of("org", task.org, "key", task.key, "lease", task.lease, "now", now, "until", now + LEASE_MS)) == 1;
    }

    void retry(Task task, long now, boolean moved) {
        long delay = Math.min(300_000, 5_000L << Math.min(6, Math.max(0, task.attempts - 1)));
        jdbc.update("""
                UPDATE external_zhaogang_statistics_refresh_task
                SET state=:state, lease_id=NULL, lease_until=0, next_retry_at=:retry,
                    attempt=IF(:moved, GREATEST(attempt-1,0), attempt), safe_error=:error
                WHERE coding_team_id=:org AND range_key=:key AND state='RUNNING' AND lease_id=:lease
                """, new MapSqlParameterSource(key(task.org, task.key)).addValue("lease", task.lease)
                .addValue("state", moved ? "QUEUED" : "FAILED").addValue("moved", moved)
                .addValue("retry", moved ? now + 1000 : now + delay)
                .addValue("error", moved ? null : "统计更新失败，请稍后重试"));
    }

    Monthly month(long org, YearMonth month) {
        return jdbc.query("""
                SELECT source_version, valid_until, worklog_synced_at, code_synced_at
                FROM external_zhaogang_statistics_month WHERE coding_team_id=:org AND stat_month=:month
                """, Map.of("org", org, "month", month.toString()), (rs, n) -> new Monthly(rs.getString(1), rs.getLong(2),
                rs.getObject(3, Long.class), rs.getObject(4, Long.class))).stream().findFirst().orElse(null);
    }

    List<Day> days(long org, Range range) {
        if (StatisticsPeriod.empty(range)) return List.of();
        return jdbc.query("""
                SELECT coding_user_id, stat_date, worklog_hours, eligible_workday, overtime_hours, overtime_days,
                       commit_count, additions, deletions, worklog_reason, commit_reason, lines_reason
                FROM external_zhaogang_statistics_day
                WHERE coding_team_id=:org AND stat_date BETWEEN :from AND :to
                """, Map.of("org", org, "from", range.from(), "to", range.to()), (rs, n) ->
                new Day(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3), rs.getBoolean(4),
                        rs.getBigDecimal(5), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                        reason(rs.getString(10)), reason(rs.getString(11)), reason(rs.getString(12))));
    }

    boolean publishMonth(Task task, YearMonth month, Monthly monthly, String policy, List<Day> days,
                         Supplier<Long> now) {
        return Boolean.TRUE.equals(write.execute(status -> {
            Monthly existing = month(task.org, month);
            if (existing != null && monthly.sourceVersion.equals(existing.sourceVersion)
                    && existing.validUntil > now.get()) return true;
            jdbc.update("""
                    DELETE FROM external_zhaogang_statistics_day
                    WHERE coding_team_id=:org AND stat_date BETWEEN :from AND :to
                    """, Map.of("org", task.org, "from", month.atDay(1), "to", month.atEndOfMonth()));
            for (int from = 0; from < days.size(); from += 500) {
                jdbc.batchUpdate("""
                        INSERT INTO external_zhaogang_statistics_day
                        (coding_team_id,coding_user_id,stat_date,worklog_hours,eligible_workday,overtime_hours,overtime_days,
                         commit_count,additions,deletions,worklog_reason,commit_reason,lines_reason)
                        VALUES (:org,:user,:date,:hours,:eligible,:overtime,:days,:commits,:additions,:deletions,:work,:code,:lines)
                        """, days.subList(from, Math.min(from + 500, days.size())).stream().map(day ->
                        new MapSqlParameterSource().addValue("org", task.org).addValue("user", day.userId())
                                .addValue("date", day.date()).addValue("hours", day.hours()).addValue("eligible", day.workday())
                                .addValue("overtime", day.overtimeHours()).addValue("days", day.overtimeDays())
                                .addValue("commits", day.commits()).addValue("additions", day.additions())
                                .addValue("deletions", day.deletions()).addValue("work", name(day.worklogReason()))
                                .addValue("code", name(day.commitReason())).addValue("lines", name(day.linesReason())))
                                .toArray(MapSqlParameterSource[]::new));
            }
            Revision revision = revision(task.org, List.of(month), true);
            if (!monthly.sourceVersion.equals(revision.monthVersion(month, policy)) || !owns(task, now.get())) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("""
                    INSERT INTO external_zhaogang_statistics_month
                    (coding_team_id,stat_month,source_version,generation,valid_until,worklog_synced_at,code_synced_at)
                    VALUES (:org,:month,:version,:generation,:until,:work,:code)
                    ON DUPLICATE KEY UPDATE source_version=VALUES(source_version),generation=VALUES(generation),
                    valid_until=VALUES(valid_until),worklog_synced_at=VALUES(worklog_synced_at),code_synced_at=VALUES(code_synced_at)
                    """, new MapSqlParameterSource().addValue("org", task.org).addValue("month", month.toString())
                    .addValue("version", monthly.sourceVersion).addValue("generation", UUID.randomUUID().toString())
                    .addValue("until", monthly.validUntil).addValue("work", monthly.worklogSyncedAt)
                    .addValue("code", monthly.codeSyncedAt));
            return true;
        }));
    }

    boolean publishReport(Task task, Revision built, String source, String policy, List<YearMonth> months, Payload payload, Supplier<Long> now) {
        Indicator commits = payload.summary() == null ? null : payload.summary().commitCount();
        boolean codeSyncPending = commits != null && commits.reasonCodes().contains(Reason.CODE_SYNC_RUNNING);
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize statistics report", error);
        }
        return Boolean.TRUE.equals(write.execute(status -> {
            Revision current = revision(task.org, months, true);
            long timestamp = now.get();
            if (current.visibility != built.visibility || !owns(task, timestamp)) return false;
            boolean retained = codeSyncPending && hasApplicableCodeReport(task, current.visibility, policy);
            if (!retained) {
                jdbc.update("""
                        INSERT INTO external_zhaogang_statistics_report
                        (coding_team_id,range_key,snapshot_id,visibility_version,source_version,policy_key,generated_at,payload)
                        VALUES (:org,:key,:snapshot,:visibility,:source,:policy,:now,:payload)
                        ON DUPLICATE KEY UPDATE snapshot_id=VALUES(snapshot_id),visibility_version=VALUES(visibility_version),
                          source_version=VALUES(source_version),policy_key=VALUES(policy_key),generated_at=VALUES(generated_at),
                          payload=VALUES(payload)
                        """, Map.of("org", task.org, "key", task.key, "snapshot", UUID.randomUUID().toString(),
                        "visibility", built.visibility, "source", source, "policy", policy, "now", timestamp, "payload", body));
            }
            String latest = current.fingerprint(months, policy);
            boolean changed = !latest.equals(source);
            jdbc.update("""
                    UPDATE external_zhaogang_statistics_refresh_task
                    SET state=:state, completed_at=:now, next_retry_at=:retry, attempt=0, lease_id=NULL,
                        lease_until=0, requested_visibility=:visibility, requested_source_version=:source, safe_error=NULL
                    WHERE coding_team_id=:org AND range_key=:key AND lease_id=:lease
                    """, Map.of("org", task.org, "key", task.key, "lease", task.lease, "now", timestamp,
                    "retry", timestamp + (retained && !changed ? CODE_SYNC_RETRY_MS : 1000),
                    "state", changed || retained ? "QUEUED" : "IDLE",
                    "visibility", current.visibility, "source", latest));
            return true;
        }));
    }

    private boolean hasApplicableCodeReport(Task task, long visibility, String policy) {
        Stored previous = stored(task.org, task.key, true);
        if (previous == null || previous.visibility != visibility || !policy.equals(previous.policy)) return false;
        Summary summary = decode(previous).summary();
        return summary != null && summary.commitCount() != null && summary.commitCount().sampleCount() > 0;
    }

    Payload decode(Stored stored) {
        try {
            return mapper.readValue(stored.payload, Payload.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read published statistics report", error);
        }
    }

    void cleanup(long before) {
        var candidates = jdbc.query("""
                SELECT * FROM external_zhaogang_statistics_refresh_task
                WHERE requested_at<:before AND (state='IDLE' OR (state='FAILED' AND attempt>=:max))
                ORDER BY requested_at LIMIT 100
                """, Map.of("before", before, "max", MAX_ATTEMPTS), this::mapTask);
        for (Task task : candidates) {
            write.executeWithoutResult(status -> deleteTerminal(task.org, task.key, before));
        }
    }

    private void deleteTerminal(long org, String key, long before) {
        var params = Map.of("org", org, "key", key, "before", before, "max", MAX_ATTEMPTS);
        if (jdbc.update("""
                DELETE FROM external_zhaogang_statistics_refresh_task WHERE coding_team_id=:org AND range_key=:key
                  AND requested_at<=:before AND (state='IDLE' OR (state='FAILED' AND attempt>=:max))
                """, params) == 1) {
            jdbc.update("DELETE FROM external_zhaogang_statistics_report WHERE coding_team_id=:org AND range_key=:key", params);
        }
    }

    private boolean owns(Task expected, long now) {
        Task locked = task(expected.org, expected.key, true);
        return locked != null && "RUNNING".equals(locked.state) && expected.lease.equals(locked.lease) && locked.leaseUntil > now;
    }

    private Map<String, Object> key(long org, String key) {
        return Map.of("org", org, "key", key);
    }

    private Task mapTask(ResultSet rs, int i) throws SQLException {
        return new Task(rs.getLong("coding_team_id"), rs.getString("range_key"), rs.getString("job_id"),
                rs.getString("state"), rs.getInt("attempt"), rs.getInt("priority_no"), rs.getString("lease_id"),
                rs.getLong("lease_until"), rs.getLong("next_retry_at"), rs.getLong("requested_at"),
                rs.getLong("completed_at"), rs.getLong("requested_visibility"),
                rs.getString("requested_source_version"), rs.getString("safe_error"));
    }

    private static Reason reason(String name) {
        return name == null ? null : Reason.valueOf(name);
    }

    private static String name(Reason reason) {
        return reason == null ? null : reason.name();
    }
}
