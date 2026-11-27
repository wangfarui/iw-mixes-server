package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.codestats.CodeBranchSync.Checkpoint;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IW_STATISTICS_TEST_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/devhub_stats_accept_[A-Za-z0-9_]+.*")
class CodeScanCheckpointMysqlTest {
    private static final long ORG = 97828851L;
    private static final long DEPOT = 271L;
    private static final String SIGNATURE = "fixture-checkpoint";

    @Test
    void storesPartitionsReusesSurvivorsAndRetiresOldRows() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", System.getenv().getOrDefault("IW_SHARED_MYSQL_TEST_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("isolated", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ZhaogangCodeScanRepoMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = session.getMapper(ZhaogangCodeScanRepoMapper.class);
            var full = CodeSyncCheckpointCodecTest.checkpoint(6, 10, false);
            var sample = full.branches().entrySet().iterator().next();
            var single = new Checkpoint(Map.of(sample.getKey(), sample.getValue()),
                    Map.of(sample.getValue(), full.heads().get(sample.getValue())));
            var singleEncoded = CodeSyncCheckpointCodec.encode(single);
            int perPart = (singleEncoded.length() - 4) * 3 / 4 + 64;
            var store = new CodeScanCheckpointStore(mapper, perPart);
            var first = insertJob(jdbc);
            store.save(first, DEPOT, full, store.read(first, DEPOT, SIGNATURE));

            var second = insertJob(jdbc);
            var previous = store.read(second, DEPOT, SIGNATURE);
            assertThat(previous.partitioned()).isTrue();
            assertThat(previous.checkpoint()).isEqualTo(full);
            var parts = jdbc.queryForList("""
                    SELECT part_no FROM external_zhaogang_code_checkpoint_part WHERE job_id=? ORDER BY part_no
                    """, Integer.class, first.getId());
            assertThat(parts).hasSizeGreaterThanOrEqualTo(3);

            var missing = CodeSyncCheckpointCodec.decode(mapper.selectCheckpointPart(first.getId(), DEPOT, parts.get(0)));
            jdbc.update("DELETE FROM external_zhaogang_code_checkpoint_part WHERE job_id=? AND part_no=?",
                    first.getId(), parts.get(0));
            // A separate SqlSession avoids reusing the mapper's result cache after the fixture deletion.
            try (var nextSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var nextStore = new CodeScanCheckpointStore(nextSession.getMapper(ZhaogangCodeScanRepoMapper.class), perPart);
                var remaining = nextStore.read(second, DEPOT, SIGNATURE);
                assertThat(remaining.checkpoint().branches()).doesNotContainKeys(missing.branches().keySet().toArray(String[]::new));
                assertThat(remaining.checkpoint().branches()).hasSize(full.branches().size() - missing.branches().size());
                nextStore.save(second, DEPOT, full, remaining);
                assertThat(partCount(jdbc, first.getId())).isZero();
                assertThat(partCount(jdbc, second.getId())).isPositive();
                jdbc.update("DELETE FROM external_zhaogang_code_scan_repo WHERE job_id=?", second.getId());
                assertThat(partCount(jdbc, second.getId())).isZero();
            }
        } finally {
            jdbc.update("""
                    DELETE r FROM external_zhaogang_code_scan_repo r
                    JOIN external_zhaogang_code_scan_job j ON j.id=r.job_id WHERE j.coding_team_id=?
                    """, ORG);
            jdbc.update("DELETE FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", ORG);
        }
    }

    @Test
    void sharesTrustedLegacyCheckpointAcrossScopesAndRetainsItsOriginalEvidence() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root",
                System.getenv().getOrDefault("IW_SHARED_MYSQL_TEST_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("isolated", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.addMapper(ZhaogangCodeScanRepoMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = session.getMapper(ZhaogangCodeScanRepoMapper.class);
            var legacy = insertJob(jdbc);
            var first = insertJob(jdbc);
            var next = insertJob(jdbc);
            jdbc.update("UPDATE external_zhaogang_code_scan_job SET scope_type='WORKBENCH_TEAM',scope_id=10 WHERE id IN (?,?)",
                    first.getId(), next.getId());
            first.setScopeType("WORKBENCH_TEAM");
            first.setScopeId(10L);
            next.setScopeType("WORKBENCH_TEAM");
            next.setScopeId(10L);
            var checkpoint = CodeSyncCheckpointCodecTest.checkpoint(1, 1, false);
            var head = checkpoint.heads().keySet().iterator().next();
            var unavailable = new Checkpoint(checkpoint.branches(), checkpoint.heads(),
                    Map.of(head, new CodeCommitGraph.Node(java.util.List.of(), false, java.util.Set.of())));
            var store = new CodeScanCheckpointStore(mapper, CodeSyncCheckpointCodec.MAX_COMPRESSED_BYTES, true);
            mapper.saveSyncCheckpoint(legacy.getId(), DEPOT, CodeSyncCheckpointCodec.encode(unavailable));
            assertThat(store.read(first, DEPOT, "different-credential").checkpoint()).isNull();

            var trusted = new Checkpoint(checkpoint.branches(), checkpoint.heads(),
                    Map.of(head, new CodeCommitGraph.Node(java.util.List.of(), true, java.util.Set.of())));
            mapper.saveSyncCheckpoint(legacy.getId(), DEPOT, CodeSyncCheckpointCodec.encode(trusted));
            var previous = store.read(first, DEPOT, "different-credential");
            assertThat(previous.checkpoint()).isEqualTo(trusted);
            store.save(first, DEPOT, trusted, previous);
            var shared = store.read(next, DEPOT, "another-credential");
            assertThat(shared.checkpoint()).isEqualTo(trusted);
            store.save(next, DEPOT, trusted, shared);
            store.retireSuperseded(next, DEPOT);
            assertThat(jdbc.queryForList("SELECT job_id FROM external_zhaogang_code_scan_repo WHERE depot_id=? AND sync_state IS NOT NULL AND job_id IN (?,?,?) ORDER BY job_id",
                    Long.class, DEPOT, legacy.getId(), first.getId(), next.getId()))
                    .containsExactly(legacy.getId(), next.getId());
        } finally {
            jdbc.update("""
                    DELETE r FROM external_zhaogang_code_scan_repo r
                    JOIN external_zhaogang_code_scan_job j ON j.id=r.job_id WHERE j.coding_team_id=?
                    """, ORG);
            jdbc.update("DELETE FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
        }
    }

    private static int partCount(JdbcTemplate jdbc, long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM external_zhaogang_code_checkpoint_part WHERE job_id=?",
                Integer.class, jobId);
    }

    private static ScanJobEntity insertJob(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_scan_job
                    (coding_team_id,scope_type,scope_id,month,scan_mode,status,stage,requested_by,requested_mode,
                     identity_signature,sync_from,sync_to)
                VALUES (?, 'SELF', 1, '2026-11', 'SYNC_V2', 'COMPLETED', 'DONE', 1, 'AUTO', ?, '2026-01-01','2026-11-27')
                """, ORG, SIGNATURE);
        long jobId = jdbc.queryForObject("SELECT MAX(id) FROM external_zhaogang_code_scan_job WHERE coding_team_id=?",
                Long.class, ORG);
        jdbc.update("""
                INSERT INTO external_zhaogang_code_scan_repo
                    (job_id,project_id,project_name,depot_id,depot_name,status)
                VALUES (?, 1, 'fixture', ?, 'fixture', 'COMPLETED')
                """, jobId, DEPOT);
        var job = new ScanJobEntity();
        job.setId(jobId);
        job.setCodingTeamId(ORG);
        job.setScopeType("SELF");
        job.setScopeId(1L);
        job.setSyncFrom(LocalDate.of(2026, 1, 1));
        job.setSyncTo(LocalDate.of(2026, 11, 27));
        return job;
    }
}
