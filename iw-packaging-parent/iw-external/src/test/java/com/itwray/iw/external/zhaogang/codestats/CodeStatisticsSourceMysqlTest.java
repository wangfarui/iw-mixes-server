package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "IW_STATISTICS_TEST_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/devhub_stats_accept_[A-Za-z0-9_]+.*")
class CodeStatisticsSourceMysqlTest {
    private static final long ORG = 97826992;
    private static final long TEAM = 9782699201L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final LocalDate RETENTION = LocalDate.of(2026, 1, 1);

    @Test
    void distinguishesActiveScanFromCompletedCoverageAndCredentialFailure() {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        var resolver = mock(CodeIdentityResolver.class);
        when(resolver.mappingIndex(List.of())).thenReturn(Map.of());
        var source = new CodeStatisticsSource(new NamedParameterJdbcTemplate(dataSource),
                mock(ZhaogangCodeIdentityMapper.class), resolver);
        try {
            insertScan(jdbc, "QUEUED");
            assertThat(read(source, "fixture-fingerprint").reason()).isEqualTo("CODE_SYNC_RUNNING");
            assertThat(read(source, null).reason()).isEqualTo("CODE_COVERAGE");
            finishScan(jdbc, 0);
            assertThat(read(source, "fixture-fingerprint").reason()).isNull();

            // A new scan of the same range takes precedence over the completed one.
            insertScan(jdbc, "RUNNING");
            assertThat(read(source, "fixture-fingerprint").reason()).isEqualTo("CODE_SYNC_RUNNING");
            finishScan(jdbc, 1);
            assertThat(read(source, "fixture-fingerprint").reason()).isEqualTo("CODE_COVERAGE");
        } finally {
            jdbc.update("DELETE FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
        }
    }

    @Test
    void readsVerifiedRepositoriesWhenOneRepositoryFails() {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        var resolver = mock(CodeIdentityResolver.class);
        when(resolver.mappingIndex(List.of())).thenReturn(Map.of());
        when(resolver.resolve(Map.of(), null, "")).thenReturn(new CodeIdentityResolver.Resolved(1L, false));
        var source = new CodeStatisticsSource(new NamedParameterJdbcTemplate(dataSource),
                mock(ZhaogangCodeIdentityMapper.class), resolver);
        insertScan(jdbc, "RUNNING");
        long scan = jdbc.queryForObject(
                "SELECT id FROM external_zhaogang_code_scan_job WHERE coding_team_id=? AND status='RUNNING'",
                Long.class, ORG);
        try {
            for (int depot = 1; depot <= 2; depot++) {
                jdbc.update("""
                        INSERT INTO external_zhaogang_code_scan_repo
                            (job_id, project_id, project_name, depot_id, depot_name, status, error_code)
                        VALUES (?, 10, 'fixture', ?, 'repo', ?, ?)
                        """, scan, depot, depot == 1 ? "COMPLETED" : "FAILED",
                        depot == 1 ? null : "CHECKPOINT_TOO_LARGE");
            }
            insertCommit(jdbc, 1, "a", scan);
            insertCommit(jdbc, 2, "b", scan - 1);
            jdbc.update("""
                    UPDATE external_zhaogang_code_scan_job
                    SET status='PARTIAL', stage='DONE', total_repos=2, done_repos=2, failed_repos=1,
                        error_code='PARTIAL_SCAN', finished_at='2026-11-26 12:01:00'
                    WHERE id=?
                    """, scan);

            var month = read(source, "fixture-fingerprint");
            assertThat(month.reason()).isNull();
            assertThat(month.repositoriesPartial()).isTrue();
            assertThat(month.data()).containsExactlyEntriesOf(Map.of(
                    new CodeStatisticsSource.Key(1L, TODAY.minusDays(1)),
                    new CodeStatisticsSource.Totals(1, 3, 2, false)));
            assertThat(month.syncedAt()).isNotNull();
            assertThat(read(source, null).reason()).isEqualTo("CODE_COVERAGE");

            jdbc.update("""
                    UPDATE external_zhaogang_code_scan_job SET error_code='PARTIAL_COVERAGE' WHERE id=?
                    """, scan);
            assertThat(read(source, "fixture-fingerprint").reason()).isEqualTo("CODE_COVERAGE");
        } finally {
            jdbc.update("DELETE FROM external_zhaogang_code_scan_repo WHERE job_id=?", scan);
            jdbc.update("DELETE FROM external_zhaogang_code_commit WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
        }
    }

    @Test
    void scanCleanupKeepsDeferredRepositoryOnlyForThatRound() {
        long scanId = 9782699202L;
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", "");
        var jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("isolated", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ZhaogangCodeCommitMapper.class);
        configuration.addMapper(ZhaogangCodeScanRepoMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            jdbc.update("""
                    INSERT INTO external_zhaogang_code_scan_repo
                        (job_id, project_id, project_name, depot_id, depot_name, status, error_code)
                    VALUES (?, 10, 'fixture', 1, 'deferred', 'SKIPPED', 'BRANCH_CHANGED')
                    """, scanId);
            insertCommit(jdbc, 1, "a", scanId - 1);
            insertCommit(jdbc, 2, "b", scanId - 1);
            insertCommit(jdbc, 3, "c", scanId);
            var mapper = session.getMapper(ZhaogangCodeCommitMapper.class);

            assertThat(mapper.cleanupFullScan(ORG, "WORKBENCH_TEAM", TEAM, "2026-11", scanId)).isEqualTo(1);
            assertThat(commitDepots(jdbc)).containsExactly(1L, 3L);
            assertThat(session.getMapper(ZhaogangCodeScanRepoMapper.class).selectIssues(scanId))
                    .extracting(repo -> repo.getErrorCode()).containsExactly("BRANCH_CHANGED");

            assertThat(mapper.cleanupFullScan(ORG, "WORKBENCH_TEAM", TEAM, "2026-11", scanId + 1)).isEqualTo(2);
            assertThat(commitDepots(jdbc)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM external_zhaogang_code_scan_repo WHERE job_id=?", scanId);
            jdbc.update("DELETE FROM external_zhaogang_code_commit WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
        }
    }

    private void insertCommit(JdbcTemplate jdbc, long depot, String sha, long lastSeen) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_commit
                    (coding_team_id, scope_type, scope_id, month, project_id, project_name, depot_id, depot_name,
                     sha, short_sha, commit_at, commit_date, diff_status, additions, deletions, last_seen_scan_id)
                VALUES (?, 'WORKBENCH_TEAM', ?, '2026-11', 10, 'fixture', ?, 'repo',
                        ?, ?, '2026-11-25 12:00:00', '2026-11-25', 'OK', 3, 2, ?)
                """, ORG, TEAM, depot, sha.repeat(40), sha, lastSeen);
    }

    private List<Long> commitDepots(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT depot_id FROM external_zhaogang_code_commit
                WHERE coding_team_id=? ORDER BY depot_id
                """, Long.class, ORG);
    }

    private void insertScan(JdbcTemplate jdbc, String status) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_scan_job
                    (coding_team_id, scope_type, scope_id, month, scan_mode, status, stage, requested_mode,
                     requested_by, sync_from, sync_to, total_repos, started_at)
                VALUES (?, 'WORKBENCH_TEAM', ?, '2026-11', 'SYNC_V2', ?, 'BRANCHES', 'AUTO',
                        1, '2026-01-01', '2026-11-26', 1, '2026-11-26 12:00:00')
                """, ORG, TEAM, status);
    }

    private void finishScan(JdbcTemplate jdbc, int failedRepos) {
        jdbc.update("""
                UPDATE external_zhaogang_code_scan_job SET status=?, stage='DONE', failed_repos=?,
                    finished_at='2026-11-26 12:01:00'
                WHERE coding_team_id=? AND status IN ('QUEUED', 'RUNNING')
                """, failedRepos == 0 ? "COMPLETED" : "PARTIAL", failedRepos, ORG);
    }

    private CodeStatisticsSource.Month read(CodeStatisticsSource source, String fingerprint) {
        var member = new Membership(TEAM, 1, "fixture member", null, "BACKEND", false, false, fingerprint, null);
        return source.read(ORG, YearMonth.from(TODAY), TODAY, RETENTION, 0, List.of(member));
    }
}
