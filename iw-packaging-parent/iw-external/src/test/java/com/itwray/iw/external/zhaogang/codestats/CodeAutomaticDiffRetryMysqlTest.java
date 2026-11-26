package com.itwray.iw.external.zhaogang.codestats;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import com.itwray.iw.external.zhaogang.team.OrganizationStatisticsDirectory.Membership;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "IW_STATISTICS_TEST_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/devhub_stats_accept_[A-Za-z0-9_]+.*")
class CodeAutomaticDiffRetryMysqlTest {
    private static final long ORG = 97829001L;
    private static final long TEAM = 97829002L;
    private static final long USER = 42L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T02:00:00Z"), CodeStatsSupport.CHINA_ZONE);
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @Test
    void schedulerRepairsProductionFailureClassesAndPersistsRetryConditions() {
        var configuration = new MybatisConfiguration(new Environment("isolated",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ZhaogangCodeCommitMapper.class);
        configuration.addMapper(ZhaogangCodeScanJobMapper.class);
        var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var commits = sql.getMapper(ZhaogangCodeCommitMapper.class);
        var jobs = sql.getMapper(ZhaogangCodeScanJobMapper.class);
        var repos = mock(ZhaogangCodeScanRepoMapper.class);
        var coding = mock(CodingOpenApiPort.class);
        var git = mock(CodeGitLineStats.class);
        var batch = mock(CodeGitLineStats.Batch.class);
        var directory = mock(CodeStatsScopeDirectory.class);
        var identities = mock(CodeIdentityResolver.class);
        var cache = mock(CodeStatsViewCache.class);
        var properties = new ZhaogangProperties();
        var target = new CodeStatsModels.AutomaticSyncTarget(CodeStatsModels.Scope.WORKBENCH_TEAM, ORG, TEAM);
        var scope = new CodeStatsModule.ScopeInfo(target.scope(), TEAM, ORG, "fixture", "https://example.invalid",
                "fixture", List.of(new CodeStatsModule.MemberCredential(USER, "fixture", "", "fixture-token")), false);
        String credentials = CodeDiffRetryPolicy.credentialSignature(scope);
        when(directory.automaticTargets()).thenReturn(List.of(target));
        when(directory.resolveAutomatic(target)).thenReturn(Optional.of(scope));
        var mapping = new IdentityEntity();
        mapping.setCodingUserId(USER);
        var identityIndex = Map.of("fixture", mapping);
        when(identities.mappingIndex(List.of())).thenReturn(identityIndex);
        when(identities.resolve(identityIndex, null, "")).thenReturn(new CodeIdentityResolver.Resolved(USER, false));
        when(git.executableAvailable()).thenReturn(true);
        when(git.batch(scope)).thenReturn(batch);
        when(coding.commitDiff("fixture-token", 1L, 1L, sha("a"))).thenThrow(
                new CodingOpenApiException("DescribeGitCommitDiff", "FailedOperation", "diff 修改文件过多", 200, null));
        when(coding.commitDiff("fixture-token", 1L, 1L, sha("b"))).thenThrow(
                new CodingOpenApiException("DescribeGitCommitDiff", "FailedOperation", "参数异常", 200, null));
        when(batch.count(argThat(row -> row != null && sha("a").equals(row.getSha())), eq("fixture-token")))
                .thenReturn(CodeGitLineStats.Result.ok(new CodingOpenApiPort.CodingCommitDiff(5279, 5, 7)));
        when(batch.count(argThat(row -> row != null && sha("b").equals(row.getSha())), eq("fixture-token")))
                .thenReturn(CodeGitLineStats.Result.ok(new CodingOpenApiPort.CodingCommitDiff(937, 0, 14)));
        when(coding.commitDiff("fixture-token", 1L, 1L, sha("c"))).thenThrow(
                new CodingOpenApiException("DescribeGitCommitDiff", "RequestLimitExceeded", "限流", 429, null));

        var worker = new CodeScanWorker(coding, jobs, repos, commits, identities, cache, properties, CLOCK, git);
        var service = new CodeScanService(jobs, repos, commits, cache, worker, properties, identities, CLOCK);
        var reportSource = new CodeStatisticsSource(new NamedParameterJdbcTemplate(dataSource),
                mock(ZhaogangCodeIdentityMapper.class), identities);
        var upstreamDate = new CodeStatisticsSource.Key(USER, DAY.minusDays(7));
        var invalidDate = new CodeStatisticsSource.Key(USER, DAY.minusDays(13));
        try {
            jdbc.update("""
                    INSERT INTO external_zhaogang_code_scan_job
                        (coding_team_id,scope_type,scope_id,month,scan_mode,status,stage,requested_by,requested_mode,
                         total_repos,sync_from,sync_to,started_at,finished_at)
                    VALUES (?, 'WORKBENCH_TEAM', ?, '2026-11', 'SYNC_V2', 'COMPLETED', 'DONE', ?, 'AUTO', 1,
                            '2026-01-01', '2026-11-26', '2026-11-26 09:55:00', '2026-11-26 09:56:00')
                    """, ORG, TEAM, USER);
            insert("a", "DIFF_UPSTREAM_LIMIT", "repo", DAY.minusDays(7), TEAM);
            insert("b", "DIFF_INVALID_ARGUMENT", "repo", DAY.minusDays(13), TEAM);
            insert("c", "DIFF_RATE_LIMITED", "repo", DAY.minusDays(4), TEAM);
            insert("d", "DIFF_PERMISSION_DENIED", "repo", DAY.minusDays(3), TEAM);
            insert("e", "DIFF_RESPONSE_LIMIT", "online.base.apijar.service", DAY.minusDays(3), TEAM);
            insert("f", "DIFF_UPSTREAM_LIMIT", "repo", DAY.minusDays(3), TEAM + 1);
            jdbc.update("UPDATE external_zhaogang_code_commit SET diff_retry_credential_signature=? WHERE coding_team_id=? AND sha=?",
                    credentials, ORG, sha("d"));

            assertThat(due(commits, credentials, false)).containsExactly(sha("c"));
            assertThat(due(commits, credentials, true)).containsExactly(sha("c"), sha("a"), sha("b"));
            assertThat(reportData(reportSource).get(upstreamDate).linesIncomplete()).isTrue();
            assertThat(reportData(reportSource).get(invalidDate).linesIncomplete()).isTrue();
            var scheduler = new CodeContinuousSyncScheduler(directory, service, properties);
            scheduler.retryFailedDiffs();
            verify(cache, timeout(5000)).bumpScanVersion(ORG + ":WORKBENCH_TEAM:" + TEAM, "diff-retry");

            var repaired = reportData(reportSource);
            assertThat(repaired.get(upstreamDate)).isEqualTo(new CodeStatisticsSource.Totals(1, 5279, 5, false));
            assertThat(repaired.get(invalidDate)).isEqualTo(new CodeStatisticsSource.Totals(1, 937, 0, false));
            assertThat(jdbc.queryForObject("""
                    SELECT update_time IS NOT NULL AND diff_retry_attempts=1
                              AND diff_retry_after='2026-11-26 10:05:00'
                      FROM external_zhaogang_code_commit WHERE coding_team_id=? AND sha=?
                    """, Boolean.class, ORG, sha("c"))).isTrue();
            assertThat(due(commits, credentials, true)).isEmpty();
            assertThat(due(commits, "new-fingerprint", true)).containsExactly(sha("d"));
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM external_zhaogang_code_scan_job
                     WHERE coding_team_id=? AND scan_mode='DIFF_RETRY' AND requested_mode='AUTO'
                       AND status='PARTIAL' AND total_diffs=3 AND done_diffs=3 AND failed_diffs=1
                    """, Integer.class, ORG)).isEqualTo(1);
            verify(coding, never()).commitDiff(anyString(), eq(1L), eq(1L), eq(sha("d")));
        } finally {
            service.shutdown();
            jdbc.update("DELETE FROM external_zhaogang_code_commit WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_code_scan_job WHERE coding_team_id=?", ORG);
            jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
        }
    }

    private Map<CodeStatisticsSource.Key, CodeStatisticsSource.Totals> reportData(CodeStatisticsSource source) {
        var member = new Membership(TEAM, USER, "fixture", null, "BACKEND", false, false, "fingerprint", null);
        return source.read(ORG, YearMonth.from(DAY), DAY, DAY.minusYears(1), 0, List.of(member)).data();
    }

    private List<String> due(ZhaogangCodeCommitMapper mapper, String credentials, boolean gitAvailable) {
        return mapper.selectAutomaticDiffRetries(ORG, "WORKBENCH_TEAM", TEAM, DAY.minusYears(1), DAY,
                        LocalDateTime.now(CLOCK), credentials, gitAvailable, CodeDiffRetryPolicy.MAX_ATTEMPTS, 5)
                .stream().map(row -> jdbc.queryForObject("SELECT sha FROM external_zhaogang_code_commit WHERE id=?",
                        String.class, row.getId())).toList();
    }

    private void insert(String hash, String status, String repo, LocalDate date, long team) {
        jdbc.update("""
                INSERT INTO external_zhaogang_code_commit
                    (coding_team_id,scope_type,scope_id,month,project_id,project_name,depot_id,depot_name,sha,
                     short_sha,commit_at,commit_date,diff_status)
                VALUES (?, 'WORKBENCH_TEAM', ?, '2026-11', 1, 'fixture', 1, ?, ?, ?, ?, ?, ?)
                """, ORG, team, repo, sha(hash), hash.repeat(8), date.atStartOfDay(), date, status);
    }

    private static String sha(String value) {
        return value.repeat(40);
    }
}
