package com.itwray.iw.external.zhaogang.statistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.itwray.iw.external.zhaogang.statistics.StatisticsModels.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IW_STATISTICS_TEST_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/devhub_stats_accept_[A-Za-z0-9_]+.*")
class StatisticsRepositoryMysqlTest {
    private static final long ORG = 97826991;
    private static final long NOW = 1_800_000_000_000L;
    private static final String KEY = "2026-11-01/2026-11-25";
    private static final String POLICY = "v1:2025-01-01";
    private static final List<YearMonth> MONTHS = List.of(YearMonth.of(2026, 11));
    private StatisticsRepository repository;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(System.getenv("IW_STATISTICS_TEST_JDBC_URL"), "root", "");
        var manager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(manager);
        repository = new StatisticsRepository(new NamedParameterJdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules(), manager);
        cleanup();
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM external_zhaogang_worklog_deleted_entry WHERE coding_team_id=?", ORG);
        jdbc.update("DELETE FROM external_zhaogang_statistics_report WHERE coding_team_id=?", ORG);
        jdbc.update("DELETE FROM external_zhaogang_statistics_refresh_task WHERE coding_team_id=?", ORG);
        jdbc.update("DELETE FROM external_zhaogang_statistics_revision WHERE coding_team_id=?", ORG);
    }

    @Test
    void concurrentEnqueueUsesOneJobAndExpiredLeaseCannotPublish() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = IntStream.range(0, 16).mapToObj(i -> (Callable<String>) () ->
                    repository.enqueue(ORG, KEY, 0, "test", 10, NOW, NOW, false).jobId()).toList();
            var futures = pool.invokeAll(calls);
            var original = repository.task(ORG, KEY, false);
            for (var future : futures) assertThat(future.get()).isEqualTo(original.jobId());
            var first = repository.claim(original, NOW);
            assertThat(repository.claim(original, NOW + 1)).isNull();
            long recoveredAt = NOW + StatisticsRepository.LEASE_MS + 1;
            var restarted = repository.claim(original, recoveredAt);
            var revision = repository.revision(ORG, MONTHS, false);
            assertThat(restarted.lease()).isNotEqualTo(first.lease());
            assertThat(repository.publishReport(first, revision, revision.fingerprint(MONTHS, POLICY),
                    POLICY, MONTHS, payload(), () -> recoveredAt)).isFalse();
            assertThat(repository.publishReport(restarted, revision, revision.fingerprint(MONTHS, POLICY),
                    POLICY, MONTHS, payload(), () -> recoveredAt)).isTrue();
            assertThat(repository.task(ORG, KEY, false).state()).isEqualTo("IDLE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletionRevisionRollsBackWithSourceAndFencesInFlightReport() {
        var task = repository.claim(repository.enqueue(ORG, KEY, 0, "test", 10, NOW, NOW, false), NOW);
        var initial = repository.revision(ORG, MONTHS, false);
        tx.executeWithoutResult(status -> {
            tombstone();
            assertThat(repository.revision(ORG, MONTHS, false).visibility()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(repository.revision(ORG, MONTHS, false)).isEqualTo(initial);
        tombstone();
        assertThat(repository.revision(ORG, MONTHS, false).visibility()).isEqualTo(1);
        assertThat(repository.publishReport(task, initial, initial.fingerprint(MONTHS, POLICY),
                POLICY, MONTHS, payload(), () -> NOW + 1)).isFalse();
        assertThat(repository.stored(ORG, KEY, false)).isNull();
    }

    @Test
    void publishesConsistentOldVersionAndPersistsRerunWhenFreshnessOnlyChanged() {
        var task = repository.claim(repository.enqueue(ORG, KEY, 0, "test", 10, NOW, NOW, false), NOW);
        var original = repository.revision(ORG, MONTHS, false);
        jdbc.update("""
                INSERT INTO external_zhaogang_statistics_revision(coding_team_id,stat_month,version_no)
                VALUES (?,'2026-11',1)
                """, ORG);
        assertThat(repository.publishReport(task, original, original.fingerprint(MONTHS, POLICY),
                POLICY, MONTHS, payload(), () -> NOW + 1)).isTrue();
        var rerun = repository.task(ORG, KEY, false);
        assertThat(rerun.state()).isEqualTo("QUEUED");
        assertThat(rerun.sourceVersion()).isEqualTo(repository.revision(ORG, MONTHS, false).fingerprint(MONTHS, POLICY));
        assertThat(repository.stored(ORG, KEY, false).visibility()).isZero();
    }

    @Test
    void retainsPublishedCodeRankingWhileScanningThenReplacesItOnSuccess() {
        publishCodeReport(NOW, new Indicator(BigDecimal.TEN, Status.READY, List.of(), 1, 1));
        var previous = repository.stored(ORG, KEY, true);
        publishCodeReport(NOW + 1, new Indicator(null, Status.UNAVAILABLE, List.of(Reason.CODE_SYNC_RUNNING), 0, 1));

        assertThat(repository.stored(ORG, KEY, true)).isEqualTo(previous);
        var retry = repository.task(ORG, KEY, false);
        assertThat(retry.state()).isEqualTo("QUEUED");
        assertThat(retry.nextRetry()).isGreaterThan(NOW + 1);
        assertThat(retry.attempts()).isZero();

        publishCodeReport(retry.nextRetry(), new Indicator(BigDecimal.valueOf(12), Status.READY, List.of(), 1, 1));
        var fresh = repository.stored(ORG, KEY, true);
        assertThat(fresh.snapshotId()).isNotEqualTo(previous.snapshotId());
        assertThat(repository.decode(fresh).summary().commitCount().value()).isEqualByComparingTo("12");
        assertThat(repository.task(ORG, KEY, false).state()).isEqualTo("IDLE");
    }

    @Test
    void neverRetainsCodeRankingAcrossVisibilityChanges() {
        publishCodeReport(NOW, new Indicator(BigDecimal.TEN, Status.READY, List.of(), 1, 1));
        var previous = repository.stored(ORG, KEY, false);
        tombstone();
        publishCodeReport(NOW + 1, new Indicator(null, Status.UNAVAILABLE, List.of(Reason.CODE_SYNC_RUNNING), 0, 1));

        var fresh = repository.stored(ORG, KEY, true);
        assertThat(fresh.snapshotId()).isNotEqualTo(previous.snapshotId());
        assertThat(fresh.visibility()).isEqualTo(1);
        assertThat(repository.decode(fresh).summary().commitCount().sampleCount()).isZero();
    }

    private void publishCodeReport(long now, Indicator commits) {
        var revision = repository.revision(ORG, MONTHS, false);
        String source = revision.fingerprint(MONTHS, POLICY);
        var task = repository.claim(repository.enqueue(ORG, KEY, revision.visibility(), source, 10, now, now, true), now);
        var report = new Payload(new Population(1, 1, 0, 1),
                new Summary(null, null, null, commits, commits), List.of(), new DataStatus(null, null, 0));
        assertThat(task).isNotNull();
        assertThat(repository.publishReport(task, revision, source, POLICY, MONTHS, report, () -> now)).isTrue();
    }

    private void tombstone() {
        jdbc.update("INSERT INTO external_zhaogang_worklog_deleted_entry (coding_team_id,coding_team_key,worklog_id) VALUES (?,'DEVHUB_STATS_ACCEPT_20261126',?)",
                ORG, 9782699101L);
    }

    private Payload payload() {
        return new Payload(new Population(0, 0, 0, 0), null, List.of(), new DataStatus(null, null, 0));
    }
}
