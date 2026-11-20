package com.itwray.iw.external.zhaogang.worklog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic publication and persisted leases. No credential values are stored here. */
@Repository
class WorklogMirrorRepository {
  record Source(
      String key,
      long teamId,
      long userId,
      long managementTeamId,
      String fingerprint,
      long through,
      long incrementAt,
      long version,
      String state,
      String warning,
      boolean blocked,
      boolean incomplete,
      long requestedFrom,
      boolean incremental,
      String leaseId,
      long historyBefore) {}

  record ScopeTimes(long requestedAt, long syncedAt) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  WorklogMirrorRepository(
      NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
  }

  void ensure(String key, long team, long user, long managementTeam, String fingerprint) {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              """
                INSERT INTO external_zhaogang_worklog_source
                (source_key,coding_team_id,coding_user_id,management_team_id,credential_fingerprint)
                VALUES (:key,:team,:user,:management,:fingerprint)
                ON DUPLICATE KEY UPDATE source_key=source_key
                """,
              Map.of(
                  "key",
                  key,
                  "team",
                  team,
                  "user",
                  user,
                  "management",
                  managementTeam,
                  "fingerprint",
                  fingerprint));
          var current =
              jdbc.queryForObject(
                  "SELECT * FROM external_zhaogang_worklog_source WHERE source_key=:key FOR UPDATE",
                  Map.of("key", key),
                  this::mapSource);
          if (!current.fingerprint().equals(fingerprint)
              || current.managementTeamId() != managementTeam) {
            // Credential changes invalidate the entire previous visibility scope, including
            // in-flight work.
            jdbc.update(
                "DELETE FROM external_zhaogang_worklog_entry WHERE source_key=:key",
                Map.of("key", key));
            jdbc.update(
                "DELETE FROM external_zhaogang_worklog_issue_snapshot WHERE source_key=:key",
                Map.of("key", key));
            jdbc.update(
                """
          UPDATE external_zhaogang_worklog_source SET management_team_id=:management,credential_fingerprint=:fingerprint,
          synced_through=0,increment_at=0,history_before=0,data_version=data_version+1,state='QUEUED',
          warning='',blocked=0,incomplete=0,requested_from=-1,incremental=1,priority_no=10,
          lease_id='',lease_until=0,retry_after=0,rerun_requested=0 WHERE source_key=:key
          """,
                Map.of("key", key, "management", managementTeam, "fingerprint", fingerprint));
          }
          // Old, unpublished releases used per-team/per-token source keys. Retain their audit rows
          // only.
          jdbc.update(
              """
        UPDATE external_zhaogang_worklog_source SET state='RETIRED',blocked=1,lease_id='',lease_until=0
        WHERE coding_team_id=:team AND coding_user_id=:user AND source_key<>:key AND state<>'RETIRED'
        """,
              Map.of("key", key, "team", team, "user", user));
        });
  }

  void unavailable(String key) {
    jdbc.update(
        """
        UPDATE external_zhaogang_worklog_source SET state='MISSING',blocked=1,lease_id='',lease_until=0,
          credential_fingerprint='',data_version=data_version+1,warning='未绑定个人令牌且关联团队管理令牌不可用'
        WHERE source_key=:key AND state<>'MISSING'
        """,
        Map.of("key", key));
  }

  Source source(String key) {
    return jdbc
        .query(
            "SELECT * FROM external_zhaogang_worklog_source WHERE source_key=:key",
            Map.of("key", key),
            this::mapSource)
        .stream()
        .findFirst()
        .orElse(null);
  }

  List<Source> sources(List<String> keys) {
    return keys.isEmpty()
        ? List.of()
        : jdbc.query(
            "SELECT * FROM external_zhaogang_worklog_source WHERE source_key IN (:keys)",
            Map.of("keys", keys),
            this::mapSource);
  }

  void queue(String key, long from, boolean incremental) {
    queue(key, from, incremental, 10);
  }

  void queue(String key, long from, boolean incremental, int priority) {
    // Existing work is shared by manual/automatic requests; a running lease is never stolen.
    jdbc.update(
        """
                UPDATE external_zhaogang_worklog_source SET state='QUEUED',requested_from=:from,
                incremental=:incremental,retry_after=0,priority_no=:priority
                WHERE source_key=:key AND state NOT IN ('QUEUED','RUNNING','MISSING','RETIRED')
                """,
        Map.of("key", key, "from", from, "incremental", incremental, "priority", priority));
  }

  void prioritize(String key, int priority) {
    jdbc.update(
        "UPDATE external_zhaogang_worklog_source SET priority_no=LEAST(priority_no,:priority) WHERE"
            + " source_key=:key AND state IN ('QUEUED','RUNNING')",
        Map.of("key", key, "priority", priority));
  }

  void incrementalRequest(String key, int priority) {
    queue(key, -1, true, priority);
    // A queued history chunk yields to fresh data; a running chunk commits then follows up.
    jdbc.update(
        """
        UPDATE external_zhaogang_worklog_source SET requested_from=-1,incremental=1,
          priority_no=LEAST(priority_no,:priority) WHERE source_key=:key AND state='QUEUED'
        """,
        Map.of("key", key, "priority", priority));
    jdbc.update(
        """
        UPDATE external_zhaogang_worklog_source SET rerun_requested=1
        WHERE source_key=:key AND state='RUNNING' AND incremental=0
        """,
        Map.of("key", key));
    prioritize(key, priority);
  }

  void requestRerun(String key) {
    jdbc.update(
        "UPDATE external_zhaogang_worklog_source SET rerun_requested=1 WHERE source_key=:key AND"
            + " state='RUNNING'",
        Map.of("key", key));
  }

  List<Source> pending(long now) {
    return jdbc.query(
        """
                SELECT * FROM external_zhaogang_worklog_source
                WHERE (state='QUEUED' AND retry_after<=:now) OR (state='RUNNING' AND lease_until<:now)
                ORDER BY priority_no, increment_at, coding_team_id, coding_user_id LIMIT 8
                """,
        Map.of("now", now),
        this::mapSource);
  }

  boolean claim(String key, String lease, long now) {
    return jdbc.update(
            """
                UPDATE external_zhaogang_worklog_source SET state='RUNNING',lease_id=:lease,lease_until=:until
                WHERE source_key=:key AND ((state='QUEUED' AND retry_after<=:now)
                  OR (state='RUNNING' AND lease_until<:now))
                """,
            Map.of("key", key, "lease", lease, "now", now, "until", now + 120_000))
        == 1;
  }

  void heartbeat(String key, String lease, long now) {
    if (jdbc.update(
            "UPDATE external_zhaogang_worklog_source SET lease_until=:until WHERE source_key=:key"
                + " AND lease_id=:lease AND state='RUNNING'",
            Map.of("key", key, "lease", lease, "until", now + 120_000))
        != 1) throw new IllegalStateException("Sync lease expired");
  }

  void fail(String key, String lease, boolean blocked, String warning, long now) {
    jdbc.update(
        """
                UPDATE external_zhaogang_worklog_source SET state=IF(rerun_requested=1,'QUEUED','FAILED'),warning=:warning,
                blocked=GREATEST(blocked,:blocked),lease_until=0,retry_after=:retry
                ,incremental=IF(rerun_requested=1,1,incremental),requested_from=IF(rerun_requested=1,-1,requested_from),rerun_requested=0
                WHERE source_key=:key AND lease_id=:lease AND state='RUNNING'
                """,
        Map.of(
            "key",
            key,
            "lease",
            lease,
            "blocked",
            blocked,
            "warning",
            warning,
            "retry",
            now + 60_000));
  }

  void publish(
      Source source,
      String lease,
      long from,
      long through,
      long now,
      List<Worklog> rows,
      Map<com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey, Issue> issues,
      boolean reconcile,
      boolean incomplete) {
    tx.executeWithoutResult(
        status -> {
          var locked =
              jdbc.query(
                      "SELECT * FROM external_zhaogang_worklog_source WHERE source_key=:key FOR"
                          + " UPDATE",
                      Map.of("key", source.key()),
                      this::mapSource)
                  .get(0);
          if (!locked.state().equals("RUNNING") || !locked.leaseId().equals(lease))
            throw new IllegalStateException("Sync lease expired");
          if (reconcile) {
            // Absence means unavailable from THIS source, never a permanent organization deletion.
            jdbc.update(
                """
                        UPDATE external_zhaogang_worklog_entry SET missing=1 WHERE source_key=:key
                        AND created_at>=:from AND created_at<=:through
                        """,
                Map.of("key", source.key(), "from", from, "through", through));
          }
          List<MapSqlParameterSource> args = new ArrayList<>();
          for (var row : rows)
            args.add(
                new MapSqlParameterSource()
                    .addValue("key", source.key())
                    .addValue("team", source.teamId())
                    .addValue("user", source.userId())
                    .addValue("id", row.id())
                    .addValue("project", row.projectName())
                    .addValue("issue", row.issueId())
                    .addValue("code", row.issueCode())
                    .addValue("start", row.startAt())
                    .addValue("created", row.createdAt())
                    .addValue("updated", row.updatedAt())
                    .addValue(
                        "hours",
                        row.hours() == null ? BigDecimal.ZERO : row.hours().max(BigDecimal.ZERO))
                    .addValue("desc", Objects.toString(row.workingDesc(), ""))
                    .addValue("now", now));
          for (int i = 0; i < args.size(); i += 500)
            jdbc.batchUpdate(
                """
                    INSERT INTO external_zhaogang_worklog_entry
                    (source_key,worklog_id,coding_team_id,coding_user_id,project_name,issue_id,issue_code,
                     start_at,created_at,updated_at,hours,working_desc,observed_at)
                    VALUES (:key,:id,:team,:user,:project,:issue,:code,:start,:created,:updated,:hours,:desc,:now)
                    ON DUPLICATE KEY UPDATE project_name=VALUES(project_name),issue_id=VALUES(issue_id),
                    issue_code=VALUES(issue_code),start_at=VALUES(start_at),created_at=VALUES(created_at),
                    updated_at=VALUES(updated_at),hours=VALUES(hours),working_desc=VALUES(working_desc),observed_at=VALUES(observed_at),missing=0
                    """,
                args.subList(i, Math.min(args.size(), i + 500))
                    .toArray(MapSqlParameterSource[]::new));
          issues.forEach(
              (key, issue) ->
                  jdbc.update(
                      """
                    INSERT INTO external_zhaogang_worklog_issue_snapshot(source_key,project_name,issue_code,issue_json,checked_at)
                    VALUES (:source,:project,:code,:json,:now)
                    ON DUPLICATE KEY UPDATE issue_json=VALUES(issue_json),checked_at=VALUES(checked_at)
                    """,
                      Map.of(
                          "source",
                          source.key(),
                          "project",
                          key.projectName(),
                          "code",
                          key.issueCode(),
                          "json",
                          encode(issue),
                          "now",
                          now)));
          long missing =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM external_zhaogang_worklog_entry WHERE source_key=:key AND"
                      + " missing=1",
                  Map.of("key", source.key()),
                  Long.class);
          long historyBefore =
              source.through() == 0 || source.requestedFrom() == -2 ? from : locked.historyBefore();
          boolean moreHistory = historyBefore > WorklogMirror.INITIAL_FROM;
          jdbc.update(
              """
                    UPDATE external_zhaogang_worklog_source SET state=IF(rerun_requested=1 OR :moreHistory,'QUEUED','READY'),synced_through=GREATEST(synced_through,:through),history_before=:historyBefore,
                    increment_at=CASE WHEN incremental=1 THEN :now ELSE increment_at END,
                    data_version=data_version+1,blocked=0,incomplete=:incomplete,warning=:warning,lease_until=0,
                    priority_no=IF(rerun_requested=1,0,IF(:moreHistory,30,priority_no)),
                    incremental=IF(rerun_requested=1,1,IF(:moreHistory,0,incremental)),requested_from=IF(rerun_requested=1,-1,IF(:moreHistory,-2,requested_from)),rerun_requested=0
                    WHERE source_key=:key AND lease_id=:lease
                    """,
              Map.of(
                  "key",
                  source.key(),
                  "lease",
                  lease,
                  "through",
                  through,
                  "moreHistory",
                  moreHistory,
                  "historyBefore",
                  historyBefore,
                  "now",
                  now,
                  "incomplete",
                  incomplete,
                  "warning",
                  missing > 0
                      ? "部分历史工时在当前令牌下已不再可见，暂不据此判断缺勤"
                      : incomplete ? "部分工时缺少必要字段或事项信息，暂不据此判断缺勤" : ""));
        });
  }

  List<Worklog> rows(
      List<String> keys, long from, long to, WorklogQueryService.EntryPosition after, int limit) {
    if (keys.isEmpty()) return List.of();
    var args =
        new MapSqlParameterSource()
            .addValue("keys", keys)
            .addValue("from", from)
            .addValue("to", to)
            .addValue("limit", limit);
    String seek = "";
    if (after != null) {
      seek =
          " AND (e.start_at,COALESCE(e.updated_at,e.created_at,0),e.worklog_id,e.coding_user_id)<(:start,:updated,:id,:user)";
      args.addValue("start", after.startAt())
          .addValue("updated", after.updatedAt())
          .addValue("id", after.id())
          .addValue(
              "user", Long.parseLong(after.key().substring(after.key().lastIndexOf(',') + 1)));
    }
    return jdbc.query(
        "SELECT e.* "
            + visible()
            + seek
            + " ORDER BY e.start_at DESC,COALESCE(e.updated_at,e.created_at,0) DESC,e.worklog_id"
            + " DESC,e.coding_user_id DESC LIMIT :limit",
        args,
        (rs, n) ->
            new Worklog(
                rs.getLong("worklog_id"),
                rs.getLong("issue_id"),
                rs.getLong("issue_code"),
                rs.getString("project_name"),
                rs.getLong("coding_user_id"),
                rs.getBigDecimal("hours"),
                rs.getString("working_desc"),
                (Long) rs.getObject("start_at"),
                (Long) rs.getObject("created_at"),
                (Long) rs.getObject("updated_at")));
  }

  BigDecimal total(List<String> keys, long from, long to) {
    if (keys.isEmpty()) return BigDecimal.ZERO;
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(e.hours),0) " + visible(),
        Map.of("keys", keys, "from", from, "to", to),
        BigDecimal.class);
  }

  long missing(String key, long from, long to) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM external_zhaogang_worklog_entry e
        WHERE source_key=:key AND missing=1 AND start_at>=:from AND start_at<:to
          AND NOT EXISTS (SELECT 1 FROM external_zhaogang_worklog_deleted_issue d
            WHERE d.coding_team_id=e.coding_team_id AND d.coding_issue_id=e.issue_id)
        """,
        Map.of("key", key, "from", from, "to", to),
        Long.class);
  }

  int projectCount(List<String> keys, long from, long to) {
    return keys.isEmpty()
        ? 0
        : jdbc.queryForObject(
            "SELECT COUNT(DISTINCT e.project_name) " + visible(),
            Map.of("keys", keys, "from", from, "to", to),
            Integer.class);
  }

  private String visible() {
    return """
                FROM external_zhaogang_worklog_entry e JOIN external_zhaogang_worklog_source s ON s.source_key=e.source_key
                WHERE e.source_key IN (:keys) AND s.blocked=0 AND e.missing=0 AND e.start_at>=:from AND e.start_at<:to
                AND NOT EXISTS (SELECT 1 FROM external_zhaogang_worklog_deleted_issue d
                    WHERE d.coding_team_id=e.coding_team_id AND d.coding_issue_id=e.issue_id)
                """;
  }

  Issue issue(String source, String project, long code) {
    return jdbc
        .query(
            "SELECT issue_json FROM external_zhaogang_worklog_issue_snapshot WHERE"
                + " source_key=:source AND project_name=:project AND issue_code=:code",
            Map.of("source", source, "project", project, "code", code),
            (rs, n) -> decode(rs.getString(1)))
        .stream()
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  ScopeTimes scope(String key, long now, boolean request) {
    jdbc.update(
        "INSERT INTO external_zhaogang_worklog_sync_scope(scope_key,requested_at) VALUES"
            + " (:key,:now) ON DUPLICATE KEY UPDATE scope_key=scope_key",
        Map.of("key", key, "now", now));
    if (request)
      jdbc.update(
          "UPDATE external_zhaogang_worklog_sync_scope SET requested_at=:now WHERE scope_key=:key",
          Map.of("key", key, "now", now));
    return jdbc.queryForObject(
        "SELECT requested_at,synced_at FROM external_zhaogang_worklog_sync_scope WHERE"
            + " scope_key=:key",
        Map.of("key", key),
        (rs, n) -> new ScopeTimes(rs.getLong(1), rs.getLong(2)));
  }

  void completeScope(String key, long requestedAt, long completedAt) {
    jdbc.update(
        "UPDATE external_zhaogang_worklog_sync_scope SET synced_at=:done WHERE scope_key=:key AND"
            + " requested_at=:request",
        Map.of("key", key, "request", requestedAt, "done", completedAt));
  }

  private Source mapSource(ResultSet rs, int row) throws SQLException {
    return new Source(
        rs.getString("source_key"),
        rs.getLong("coding_team_id"),
        rs.getLong("coding_user_id"),
        rs.getLong("management_team_id"),
        rs.getString("credential_fingerprint"),
        rs.getLong("synced_through"),
        rs.getLong("increment_at"),
        rs.getLong("data_version"),
        rs.getString("state"),
        rs.getString("warning"),
        rs.getBoolean("blocked"),
        rs.getBoolean("incomplete"),
        rs.getLong("requested_from"),
        rs.getBoolean("incremental"),
        rs.getString("lease_id"),
        rs.getLong("history_before"));
  }

  private String encode(Issue issue) {
    try {
      return json.writeValueAsString(issue);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode issue snapshot", e);
    }
  }

  private Issue decode(String value) {
    try {
      return json.readValue(value, Issue.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot read issue snapshot", e);
    }
  }
}
