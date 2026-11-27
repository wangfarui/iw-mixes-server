package com.itwray.iw.external.zhaogang.codestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "IW_STATISTICS_TEST_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/devhub_stats_accept_[A-Za-z0-9_]+.*")
class CodeStatisticsSourceMysqlTest {
    private static final long ORG = 97826992;
    private static final long TEAM = 9782699201L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final LocalDate RETENTION = LocalDate.of(2026, 1, 1);

    @Test
    void readsSharedRowsOnceAndPreservesCoveredDataDuringPartialOrNarrowedAccess() {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root",
                System.getenv().getOrDefault("IW_SHARED_MYSQL_TEST_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("isolated", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.addMapper(ZhaogangCodeCommitMapper.class);
        configuration.addMapper(ZhaogangCodeIdentityMapper.class);
        var directory = new CodeRepositoryAccess(new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper(), mock(CodingOpenApiPort.class));

        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var resolver = new CodeIdentityResolver(session.getMapper(ZhaogangCodeIdentityMapper.class));
            var source = new CodeStatisticsSource(session.getMapper(ZhaogangCodeCommitMapper.class), resolver, directory);
            assertThat(read(source, "wide", "second").reason()).isEqualTo("CODE_COVERAGE");

            insertGrant(jdbc, "wide", "[1, 2]");
            insertGrant(jdbc, "second", "[1]");
            insertGrant(jdbc, "narrow", "[1]");
            jdbc.update("""
                    INSERT INTO external_zhaogang_code_identity
                    (coding_team_id,scope_type,scope_id,identity_type,identity_key,coding_user_id,source,updated_by)
                    VALUES (?, 'SELF', 1, 'NAME', ?, 1, 'DEFAULT', 1)
                    """, ORG, CodeStatsSupport.nameKey("fixture member"));
            jdbc.update("""
                    INSERT INTO external_zhaogang_code_scan_job
                    (coding_team_id,scope_type,scope_id,month,scan_mode,status,stage,requested_mode,requested_by,
                     sync_from,sync_to,started_at,finished_at)
                    VALUES (?, 'SELF', 1, '2026-11', 'SYNC_V2', 'PARTIAL', 'DONE', 'MANUAL', 1,
                            '2026-01-01','2026-11-26','2026-11-26 12:00:00','2026-11-26 12:01:00')
                    """, ORG);
            long scan = jdbc.queryForObject("SELECT id FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", Long.class, ORG);
            jdbc.update("""
                    INSERT INTO external_zhaogang_code_scan_repo
                    (job_id,project_id,project_name,depot_id,depot_name,status,shared_checkpoint)
                    VALUES (?,10,'fixture',1,'repo-1','COMPLETED',1), (?,10,'fixture',2,'repo-2','FAILED',0)
                    """, scan, scan);
            insertCommit(jdbc, 1, "a", "OK", 3, 2);
            insertCommit(jdbc, 2, "a", "PENDING", null, null);

            var key = new CodeStatisticsSource.Key(1, TODAY.minusDays(1));
            var partial = read(source, "wide", "second");
            assertThat(partial.data()).containsExactlyEntriesOf(Map.of(key, new CodeStatisticsSource.Totals(2, 3, 2, true)));
            assertThat(partial.repositoriesPartial()).isTrue();
            assertThat(partial.syncedAt()).isNotNull();

            var narrowed = read(source, "narrow", "second");
            assertThat(narrowed.data()).containsExactlyEntriesOf(Map.of(key, new CodeStatisticsSource.Totals(1, 3, 2, false)));
            assertThat(narrowed.repositoriesPartial()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_zhaogang_shared_code_commit WHERE coding_team_id=?",
                    Integer.class, ORG)).isEqualTo(2);
        } finally {
            directory.shutdown();
            jdbc.update("""
                    DELETE r FROM external_zhaogang_code_scan_repo r JOIN external_zhaogang_code_scan_job j ON j.id=r.job_id
                    WHERE j.coding_team_id=?
                    """, ORG);
            for (String table : List.of("code_scan_job", "code_identity", "shared_code_commit", "code_access", "statistics_revision")) {
                jdbc.update("DELETE FROM external_zhaogang_" + table + " WHERE coding_team_id=?", ORG);
            }
        }
    }

    @Test
    void coverageUsesVisibleCompletedRepositoriesAndOneCheckpointPerRange() {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root",
                System.getenv().getOrDefault("IW_SHARED_MYSQL_TEST_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var access = new CodeRepositoryAccess(new NamedParameterJdbcTemplate(dataSource), transactions,
                new ObjectMapper(), mock(CodingOpenApiPort.class));
        var from = LocalDate.of(2026, 11, 1);
        var to = from.plusDays(9);
        var lastFinished = to.atTime(15, 0);
        try {
            transactions.executeWithoutResult(transaction -> {
                transaction.setRollbackOnly();
                insertScan(jdbc, ORG, 1, "SYNC", "COMPLETED", false, from, to, lastFinished);
                insertScan(jdbc, ORG, 1, "SYNC_V2", "COMPLETED", true, from, to, lastFinished.minusHours(1));
                insertScan(jdbc, ORG, 2, "SNAPSHOT_V1", "COMPLETED", true, from, to, lastFinished.minusHours(2));
                insertScan(jdbc, ORG, 3, "SYNC_V2", "COMPLETED", true, from, from.plusDays(3), lastFinished.minusHours(1));
                insertScan(jdbc, ORG, 3, "SYNC_V2", "COMPLETED", true, from.plusDays(4), to, lastFinished.minusHours(1));
                insertScan(jdbc, ORG, 3, "SYNC_V2", "FAILED", true, from, to, lastFinished.plusHours(4));
                insertScan(jdbc, ORG, 3, "SYNC_V2", "COMPLETED", true, from, to, null);
                insertScan(jdbc, ORG, 3, "FULL", "COMPLETED", true, from, to, lastFinished.plusHours(3));
                insertScan(jdbc, ORG + 1, 3, "SYNC_V2", "COMPLETED", true, from, to, lastFinished.plusHours(2));
                insertScan(jdbc, ORG, 4, "SYNC_V2", "COMPLETED", true, from, to, lastFinished.plusHours(1));

                var covered = new CodeRepositoryAccess.Catalog(Set.of(1L, 2L), true, 0);
                assertThat(access.coverage(ORG, covered, from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, false, lastFinished));
                assertThat(access.coverage(ORG, covered, from, to, lastFinished.minusHours(2)))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, true, lastFinished.minusHours(2)));
                assertThat(access.coverage(ORG, covered, from, to.plusDays(1)))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, true, lastFinished));
                assertThat(access.coverage(ORG, new CodeRepositoryAccess.Catalog(Set.of(1L, 3L), true, 0), from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, true, lastFinished));
                assertThat(access.coverage(ORG, new CodeRepositoryAccess.Catalog(Set.of(1L), false, 0), from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, true, lastFinished));
                assertThat(access.coverage(ORG, new CodeRepositoryAccess.Catalog(Set.of(5L), true, 0), from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(false, true, null));
                assertThat(access.coverage(ORG, new CodeRepositoryAccess.Catalog(Set.of(), true, 0), from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(true, false, null));
                assertThat(access.coverage(ORG, new CodeRepositoryAccess.Catalog(Set.of(), false, 0), from, to))
                        .isEqualTo(new CodeRepositoryAccess.Coverage(false, true, null));
            });
        } finally {
            access.shutdown();
        }
    }

    private static void insertScan(JdbcTemplate jdbc, long org, long depot, String mode, String status, boolean shared,
                                   LocalDate from, LocalDate to, LocalDateTime finishedAt) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_scan_job
                (coding_team_id,scope_type,scope_id,month,scan_mode,status,stage,requested_mode,requested_by,
                 sync_from,sync_to,finished_at)
                VALUES (?, 'SELF', 1, '2026-11', ?, 'PARTIAL', 'DONE', 'MANUAL', 1, ?, ?, ?)
                """, org, mode, from, to, finishedAt);
        jdbc.update("""
                INSERT INTO external_zhaogang_code_scan_repo
                (job_id,project_id,project_name,depot_id,depot_name,status,shared_checkpoint)
                VALUES (LAST_INSERT_ID(),10,'fixture',?,'repo',?,?)
                """, depot, status, shared);
    }

    private static void insertGrant(JdbcTemplate jdbc, String fingerprint, String depots) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_access
                (coding_team_id,credential_fingerprint,depot_ids,checked_at,complete) VALUES (?,?,?,1,1)
                """, ORG, fingerprint, depots);
    }

    private static void insertCommit(JdbcTemplate jdbc, long depot, String sha, String status, Integer additions, Integer deletions) {
        jdbc.update("""
                INSERT INTO external_zhaogang_shared_code_commit
                (coding_team_id,month,project_id,project_name,depot_id,depot_name,sha,short_sha,
                 author_name_key,commit_at,commit_date,diff_status,additions,deletions)
                VALUES (?, '2026-11', 10,'fixture', ?, 'repo', ?, ?, ?, '2026-11-25 12:00:00','2026-11-25',?,?,?)
                """, ORG, depot, sha.repeat(40), sha, CodeStatsSupport.nameKey("fixture member"), status, additions, deletions);
    }

    private static CodeStatisticsSource.Month read(CodeStatisticsSource source, String first, String second) {
        var a = new Membership(TEAM, 1, "fixture member", null, "BACKEND", false, false, first, null);
        var b = new Membership(TEAM + 1, 1, "fixture member", null, "BACKEND", false, false, second, null);
        return source.read(ORG, YearMonth.from(TODAY), TODAY, RETENTION, List.of(a, b));
    }
}
