package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Repository
class WorklogDeletedIssues {

    private static final int QUERY_BATCH_SIZE = 1000;
    private final NamedParameterJdbcTemplate jdbc;

    WorklogDeletedIssues(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long revision(long codingTeamId) {
        return jdbc.queryForList("""
                SELECT revision FROM external_zhaogang_worklog_deletion_revision
                WHERE coding_team_id = :teamId
                """, Map.of("teamId", codingTeamId), Long.class).stream().findFirst().orElse(0L);
    }

    Set<Long> find(long codingTeamId, Collection<Long> issueIds) {
        var ids = new ArrayList<>(issueIds);
        Set<Long> result = new HashSet<>();
        for (int start = 0; start < ids.size(); start += QUERY_BATCH_SIZE) {
            int end = Math.min(start + QUERY_BATCH_SIZE, ids.size());
            result.addAll(jdbc.queryForList("""
                    SELECT coding_issue_id FROM external_zhaogang_worklog_deleted_issue
                    WHERE coding_team_id = :teamId AND coding_issue_id IN (:ids)
                    """, Map.of("teamId", codingTeamId, "ids", ids.subList(start, end)), Long.class));
        }
        return result;
    }

    @Transactional
    public void record(long codingTeamId, Map<Long, IssueKey> confirmed) {
        if (confirmed.isEmpty()) return;
        var team = Map.of("teamId", codingTeamId);
        // 按团队串行化新增，避免同一事项重复发现时递增版本。
        jdbc.update("""
                INSERT INTO external_zhaogang_worklog_deletion_revision (coding_team_id)
                VALUES (:teamId) ON DUPLICATE KEY UPDATE coding_team_id = coding_team_id
                """, team);
        jdbc.queryForObject("""
                SELECT revision FROM external_zhaogang_worklog_deletion_revision
                WHERE coding_team_id = :teamId FOR UPDATE
                """, team, Long.class);
        Set<Long> existing = find(codingTeamId, confirmed.keySet());
        var inserts = confirmed.entrySet().stream().filter(entry -> !existing.contains(entry.getKey()))
                .map(entry -> Map.of("teamId", codingTeamId, "issueId", entry.getKey(),
                        "project", entry.getValue().projectName(), "code", entry.getValue().issueCode()))
                .toList();
        if (inserts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO external_zhaogang_worklog_deleted_issue
                (coding_team_id, coding_issue_id, project_name, issue_code)
                VALUES (:teamId, :issueId, :project, :code)
                """, inserts.toArray(Map[]::new));
        jdbc.update("""
                UPDATE external_zhaogang_worklog_deletion_revision
                SET revision = revision + 1 WHERE coding_team_id = :teamId
                """, team);
    }
}
