package com.itwray.iw.external.zhaogang.worklog;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;

/** Confirmed remote deletions are team-wide facts, independent of a viewer's PAT. */
@Service
public class WorklogMutationJournal {
    private final NamedParameterJdbcTemplate jdbc;
    public WorklogMutationJournal(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Set<Long> deletedWorklogs(long teamId) {
        return Set.copyOf(jdbc.queryForList("SELECT worklog_id FROM external_zhaogang_worklog_deleted_entry WHERE coding_team_id=:team",
                Map.of("team", teamId), Long.class));
    }

    @Transactional
    public void worklogDeleted(long teamId, String teamKey, long worklogId) {
        var args = Map.of("team", teamId, "teamKey", teamKey, "id", worklogId);
        jdbc.update("INSERT INTO external_zhaogang_worklog_deletion_revision(coding_team_id) VALUES(:team) ON DUPLICATE KEY UPDATE coding_team_id=coding_team_id", args);
        jdbc.queryForObject("SELECT revision FROM external_zhaogang_worklog_deletion_revision WHERE coding_team_id=:team FOR UPDATE", args, Long.class);
        int added = jdbc.update("INSERT IGNORE INTO external_zhaogang_worklog_deleted_entry(coding_team_id,coding_team_key,worklog_id) VALUES(:team,:teamKey,:id)", args);
        if (added > 0) jdbc.update("UPDATE external_zhaogang_worklog_deletion_revision SET revision=revision+1 WHERE coding_team_id=:team", args);
    }

    @Transactional
    public void issueDeleted(long teamId, String project, long code) {
        var args = Map.of("team", teamId, "project", project, "code", code);
        jdbc.update("INSERT INTO external_zhaogang_worklog_deletion_revision(coding_team_id) VALUES(:team) ON DUPLICATE KEY UPDATE coding_team_id=coding_team_id", args);
        jdbc.queryForObject("SELECT revision FROM external_zhaogang_worklog_deletion_revision WHERE coding_team_id=:team FOR UPDATE", args, Long.class);
        int added = jdbc.update("INSERT IGNORE INTO external_zhaogang_worklog_deleted_issue_key(coding_team_id,project_name,issue_code) VALUES(:team,:project,:code)", args);
        if (added > 0) jdbc.update("UPDATE external_zhaogang_worklog_deletion_revision SET revision=revision+1 WHERE coding_team_id=:team", args);
    }
}
