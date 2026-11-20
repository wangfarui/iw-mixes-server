package com.itwray.iw.external.zhaogang.worklog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.mapper.ZhaogangWorklogSyncTargetMapper;
import com.itwray.iw.external.zhaogang.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Day;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.DayType;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Schedule;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.*;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Scope;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in: only an isolated MySQL database named devhub_worklog_test is accepted. */
@EnabledIfEnvironmentVariable(named = "IW_WORKLOG_TEST_JDBC_URL", matches = ".+")
class WorklogMirrorIntegrationTest {
  private final Context context =
      new Context("synthetic-personal", 101, "测试成员", "", 10, "fixture", "https://fixture.invalid");
  private final MemberCredential member =
      new MemberCredential(101, "测试成员", "", "synthetic-personal");
  private final List<Worklog> remote = new ArrayList<>();
  private final MutableClock clock = new MutableClock();
  private final CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
  private final CodingCredentialService credentials = mock(CodingCredentialService.class);
  private final ZhaogangWorklogSyncTargetMapper targets =
      mock(ZhaogangWorklogSyncTargetMapper.class);
  private final WorkbenchTeamModule teams = mock(WorkbenchTeamModule.class);
  private final CodingIssueSnapshotLoader loader = mock(CodingIssueSnapshotLoader.class);
  private final WorklogDeletedIssues deleted = mock(WorklogDeletedIssues.class);
  private NamedParameterJdbcTemplate jdbc;
  private WorklogMirrorRepository repo;
  private WorklogMirror mirror;

  @BeforeEach
  void setup() {
    String url = System.getenv("IW_WORKLOG_TEST_JDBC_URL");
    if (!url.startsWith("jdbc:mysql://127.0.0.1:") || !url.contains("/devhub_worklog_test?"))
      throw new IllegalArgumentException("Isolated database required");
    var ds = new DriverManagerDataSource(url, "root", "");
    var sql =
        new ResourceDatabasePopulator(
            new FileSystemResource("scripts/iw-external-migrate-20261120-worklog-mirror.sql"),
            new FileSystemResource(
                "scripts/iw-external-migrate-20261113-worklog-deleted-issue.sql"));
    sql.execute(ds);
    sql.execute(ds); // Migration is repeatable on MySQL.
    jdbc = new NamedParameterJdbcTemplate(ds);
    for (String table : List.of("entry", "source", "issue_snapshot", "sync_scope"))
      jdbc.getJdbcTemplate().execute("DELETE FROM external_zhaogang_worklog_" + table);
    repo =
        new WorklogMirrorRepository(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            new ObjectMapper());
    mirror =
        new WorklogMirror(
            repo,
            credentials,
            teams,
            coding,
            loader,
            deleted,
            new ZhaogangProperties(),
            targets,
            clock);
    when(credentials.token(10, 101)).thenReturn(Optional.of(member.token()));
    when(loader.loadForWorklogs(anyMap(), anyBoolean())).thenReturn(Map.of());
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), anyInt(), eq(1000)))
        .thenAnswer(
            call -> {
              long from = call.getArgument(1), to = call.getArgument(2), user = call.getArgument(3);
              int offset = call.getArgument(4);
              var matching =
                  remote.stream()
                      .filter(
                          w -> w.userId() == user && w.createdAt() >= from && w.createdAt() <= to)
                      .toList();
              return new WorklogPage(matching.stream().skip(offset).limit(1000).toList());
            });
  }

  @AfterEach
  void close() {
    if (mirror != null) mirror.shutdown();
  }

  @Test
  void initialReadsQueueWithoutCallingCodingAndPersistAcrossInstances() {
    assertThat(mirror.status(context, null, List.of(member)).running()).isTrue();
    assertThat(
            mirror.rows(
                context, null, List.of(member), date("2026-10-01"), date("2026-12-01"), null, 10))
        .isEmpty();
    verifyNoInteractions(coding);
    remote.add(row(1, "2026-10-15", clock.millis() - 86_400_000, 4));
    run();
    assertThat(mirror.total(context, null, List.of(member), date("2026-10-01"), date("2026-11-01")))
        .isEqualByComparingTo("4");
    var restarted =
        new WorklogMirror(
            repo,
            credentials,
            teams,
            coding,
            loader,
            deleted,
            new ZhaogangProperties(),
            targets,
            clock);
    try {
      clearInvocations(coding);
      assertThat(
              restarted.rows(
                  context, null, List.of(member), date("2026-10-01"), date("2026-11-01"), null, 10))
          .hasSize(1);
      verifyNoInteractions(coding);
    } finally {
      restarted.shutdown();
    }
  }

  @Test
  void backdatedNewRecordAndOverlapAreIdempotentAndNoChangeAdvancesSuccessTime() {
    mirror.status(context, null, List.of(member));
    run();
    var first = mirror.status(context, null, List.of(member)).syncedAt();
    clock.advance(900_000);
    remote.add(row(1, "2026-10-16", clock.millis() - 1000, 3));
    mirror.request(context, null, List.of(member));
    run();
    assertThat(mirror.total(context, null, List.of(member), date("2026-10-01"), date("2026-11-01")))
        .isEqualByComparingTo("3");
    assertThat(mirror.status(context, null, List.of(member)).syncedAt()).isNotEqualTo(first);
    var second = mirror.status(context, null, List.of(member)).syncedAt();
    clock.advance(900_000);
    mirror.request(context, null, List.of(member));
    run();
    assertThat(mirror.status(context, null, List.of(member)).syncedAt()).isNotEqualTo(second);
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject("SELECT COUNT(*) FROM external_zhaogang_worklog_entry", Long.class))
        .isEqualTo(1);
  }

  @Test
  void failedPaginationDoesNotPublishOrAdvanceCursorAndPermissionFailureBlocksOldRows() {
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    mirror.status(context, null, List.of(member));
    run();
    var original = repo.source(key());
    var time = mirror.status(context, null, List.of(member)).syncedAt();
    clock.advance(900_000);
    mirror.request(context, null, List.of(member));
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), eq(0), eq(1000)))
        .thenReturn(new WorklogPage(Collections.nCopies(1000, remote.get(0))));
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), eq(1000), eq(1000)))
        .thenThrow(new RuntimeException("synthetic timeout"));
    run();
    assertThat(repo.source(key()).through()).isEqualTo(original.through());
    assertThat(repo.source(key()).version()).isEqualTo(original.version());
    assertThat(mirror.status(context, null, List.of(member)).syncedAt()).isEqualTo(time);
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
    clock.advance(900_000);
    mirror.request(context, null, List.of(member));
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), eq(0), eq(1000)))
        .thenThrow(
            new CodingOpenApiException(
                "DescribeAllProjectsIssueWorkLogList", "Forbidden", "synthetic denied"));
    run();
    assertThat(repo.source(key()).blocked()).isTrue();
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isZero();
    assertThat(mirror.status(context, null, List.of(member)).syncedAt()).isEqualTo(time);
    verify(coding, never())
        .worklogPage(
            eq("synthetic-management"), anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void reconciliationMovesDatesAndMakesMissingSourceRowsPartialWithoutPermanentDeletion() {
    remote.add(row(1, "2026-10-19", clock.millis() - 86_400_000, 4));
    mirror.status(context, null, List.of(member));
    run();
    remote.set(0, row(1, "2026-11-19", remote.get(0).createdAt(), 6));
    mirror.reconcile(context, null, List.of(member), 0);
    run();
    assertThat(mirror.total(context, null, List.of(member), date("2026-10-01"), date("2026-11-01")))
        .isZero();
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("6");
    remote.clear();
    mirror.reconcile(context, null, List.of(member), 0);
    run();
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isZero();
    assertThat(mirror.problem(repo.source(key()), date("2026-11-01"), date("2026-12-01")))
        .isNotNull();
    assertThat(mirror.problem(repo.source(key()), date("2026-10-01"), date("2026-11-01"))).isNull();
    assertThat(repo.source(key()).warning()).contains("不再可见");
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT COUNT(*) FROM external_zhaogang_worklog_deleted_issue", Long.class))
        .isZero();
  }

  @Test
  void aLostLeaseCannotPublishAndUnstableReconciliationPreservesPreviousRows() {
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    mirror.status(context, null, List.of(member));
    assertThat(repo.claim(key(), "old", clock.millis())).isTrue();
    var source = repo.source(key());
    clock.advance(121_000);
    assertThat(repo.claim(key(), "new", clock.millis())).isTrue();
    assertThatThrownBy(
            () ->
                repo.publish(
                    source,
                    "old",
                    0,
                    clock.millis(),
                    clock.millis(),
                    remote,
                    Map.of(),
                    false,
                    false))
        .isInstanceOf(IllegalStateException.class);
    mirror.run(key(), "new");
    run();
    mirror.reconcile(context, null, List.of(member), 0);
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new WorklogPage(List.of()), new WorklogPage(remote));
    run();
    assertThat(repo.source(key()).warning()).contains("分页结果发生变化");
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
  }

  @Test
  void sqlPaginationKeepsFullTotalAndRejectsCrossVersionCursor() {
    for (int i = 1; i <= 11; i++) remote.add(row(i, "2026-11-19", clock.millis() - 1000, 1));
    mirror.status(context, null, List.of(member));
    run();
    var query =
        new WorklogQueryService(
            coding,
            new CodingIssueLinkBuilder(),
            new ZhaogangProperties(),
            deleted,
            mock(WorklogIssueChecks.class),
            mirror);
    try {
      var page =
          query.pageLocalEntries(
              context,
              new Team(10, "测试", "https://fixture.invalid"),
              Scope.SELF,
              null,
              List.of(member),
              date("2026-11-01"),
              date("2026-12-01"),
              null,
              null,
              0);
      assertThat(page.entries().items()).hasSize(10);
      assertThat(page.entries().totalHours()).isEqualByComparingTo("11");
      var next = WorklogQueryService.EntryPosition.decode(page.nextCursor());
      assertThatThrownBy(
              () ->
                  query.pageLocalEntries(
                      context,
                      new Team(10, "测试", "https://fixture.invalid"),
                      Scope.SELF,
                      null,
                      List.of(member),
                      date("2026-11-02"),
                      date("2026-12-01"),
                      null,
                      next,
                      0))
          .isInstanceOf(CodingOpenApiException.class)
          .hasMessageContaining("第一页");
      assertThat(
              query
                  .pageLocalEntries(
                      context,
                      new Team(10, "测试", "https://fixture.invalid"),
                      Scope.SELF,
                      null,
                      List.of(member),
                      date("2026-11-01"),
                      date("2026-12-01"),
                      null,
                      next,
                      0)
                  .entries()
                  .items())
          .hasSize(1);
      clock.advance(900_000);
      mirror.request(context, null, List.of(member));
      run();
      assertThatThrownBy(
              () ->
                  query.pageLocalEntries(
                      context,
                      new Team(10, "测试", "https://fixture.invalid"),
                      Scope.SELF,
                      null,
                      List.of(member),
                      date("2026-11-01"),
                      date("2026-12-01"),
                      null,
                      next,
                      0))
          .isInstanceOf(CodingOpenApiException.class)
          .hasMessageContaining("第一页");
    } finally {
      query.shutdown();
    }
  }

  @Test
  void boundPersonalWinsForBothMembershipTypesAndNeverMergesManagerProjects() {
    var ordinary = new WorklogMember(101, "测试成员", "", false);
    var manual = new WorklogMember(202, "手动成员", "", true);
    when(credentials.tokens(10, List.of(101L, 202L)))
        .thenReturn(Map.of(101L, "personal-a", 202L, "personal-b"));
    var selected =
        WorklogScopeDirectory.preferredMemberCredentials(
            credentials,
            10,
            List.of(ordinary, manual),
            new TeamStatisticsCredential("synthetic-management"));
    assertThat(selected)
        .extracting(MemberCredential::token)
        .containsExactly("personal-a", "personal-b");
    assertThat(selected).noneMatch(MemberCredential::managedStatistics);
    when(credentials.tokens(10, List.of(101L, 202L))).thenReturn(Map.of(101L, "personal-a"));
    selected =
        WorklogScopeDirectory.preferredMemberCredentials(
            credentials,
            10,
            List.of(ordinary, manual),
            new TeamStatisticsCredential("synthetic-management"));
    assertThat(selected.get(1).managedStatistics()).isTrue();
    assertThat(selected.get(1).token()).isEqualTo("synthetic-management");
    var manager = new MemberCredential(101, "测试成员", "", "synthetic-management", true);
    assertThat(mirror.key(context, 1L, manager)).isEqualTo(mirror.key(context, 2L, manager));
    assertThat(mirror.key(context, 1L, member)).isEqualTo(mirror.key(context, 2L, member));
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 4));
    mirror.status(context, null, List.of(member));
    run();
    assertThat(mirror.total(context, 1L, List.of(manager), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("4");
  }

  @Test
  void pageReportsAreLocalAndIncompleteMembersDoNotBecomeAbsent() {
    var checks = mock(WorklogIssueChecks.class);
    var query =
        new WorklogQueryService(
            coding,
            new CodingIssueLinkBuilder(),
            new ZhaogangProperties(),
            deleted,
            checks,
            mirror);
    try {
      LocalDate day = date("2026-11-19");
      var schedule = new Schedule("2026-11", 1, List.of(new Day(day, DayType.WORKDAY, false)));
      var initial =
          query.queryRange(
              context,
              Scope.WORKBENCH_TEAM,
              1L,
              List.of(member),
              day,
              day.plusDays(1),
              schedule,
              Map.of(),
              date("2026-11-20"),
              0,
              false);
      assertThat(initial.absences().members()).isEmpty();
      assertThat(initial.statistics().coverage().partial()).isTrue();
      verifyNoInteractions(coding, checks);
      remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
      run();
      clearInvocations(coding);
      var complete =
          query.queryRange(
              context,
              Scope.WORKBENCH_TEAM,
              1L,
              List.of(member),
              day,
              day.plusDays(1),
              schedule,
              Map.of(),
              date("2026-11-20"),
              0,
              false);
      assertThat(complete.statistics().dailyTotals().get(0).hours()).isEqualByComparingTo("8");
      verifyNoInteractions(coding, checks);
    } finally {
      query.shutdown();
    }
  }

  @Test
  void credentialChangedDuringNetworkIoCannotPublishOldSnapshot() {
    mirror.status(context, null, List.of(member));
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              when(credentials.token(10, 101)).thenReturn(Optional.of("synthetic-new"));
              return new WorklogPage(List.of(row(1, "2026-11-19", clock.millis() - 1000, 8)));
            });
    run();
    assertThat(repo.source(key()).through()).isZero();
    assertThat(repo.source(key()).blocked()).isTrue();
  }

  @Test
  void registrationDuringActiveTaskPersistsFollowupAndIgnoresManualCooldown() {
    mirror.status(context, null, List.of(member));
    assertThat(repo.claim(key(), "active", clock.millis())).isTrue();
    mirror.afterRegistration(context, member);
    mirror.run(key(), "active");
    assertThat(repo.source(key()).state()).isEqualTo("QUEUED");
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    run();
    assertThat(repo.source(key()).state()).isEqualTo("READY");
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
    mirror.afterRegistration(context, member);
    assertThat(repo.source(key()).state()).isEqualTo("QUEUED");
  }

  @Test
  void partialTeamFailureKeepsWholeScopeTimeAndSafeResponseContainsNoCredentials()
      throws Exception {
    var second = new MemberCredential(202, "第二成员", "", "synthetic-second");
    when(credentials.token(10, 202)).thenReturn(Optional.of(second.token()));
    var members = List.of(member, second);
    mirror.status(context, 1L, members);
    run();
    String initial = mirror.status(context, 1L, members).syncedAt();
    assertThat(initial).isNotBlank();
    clock.advance(900_000);
    mirror.request(context, 1L, members);
    when(coding.worklogPage(eq(second.token()), anyLong(), anyLong(), eq(202L), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("synthetic timeout"));
    run();
    var failed = mirror.status(context, 1L, members);
    assertThat(failed.syncedAt()).isEqualTo(initial);
    assertThat(failed.partial()).isTrue();
    assertThat(new ObjectMapper().writeValueAsString(failed))
        .doesNotContain(member.token(), second.token(), repo.source(key()).fingerprint());
  }

  @Test
  void issuePermissionRevocationHidesPreviousTitleWithoutDiscardingVisibleWorklogs() {
    var key = new CodingIssueSnapshotLoader.IssueKey("fixture", 1);
    var issue = new Issue(1, "SUB_TASK", "子工作项", "旧标题", "验收项目", true, 10, "", "");
    when(loader.loadForWorklogs(anyMap(), anyBoolean()))
        .thenReturn(
            Map.of(
                member.token(),
                Map.of(
                    key,
                    new CodingIssueSnapshotLoader.CheckedIssue(
                        new CodingIssueSnapshotLoader.Lookup(issue, null), clock.instant()))));
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    mirror.status(context, null, List.of(member));
    run();
    assertThat(mirror.issue(context, null, member, remote.get(0)).title()).isEqualTo("旧标题");
    clock.advance(900_000);
    when(loader.loadForWorklogs(anyMap(), anyBoolean()))
        .thenReturn(
            Map.of(
                member.token(),
                Map.of(
                    key,
                    new CodingIssueSnapshotLoader.CheckedIssue(
                        new CodingIssueSnapshotLoader.Lookup(
                            null,
                            new CodingOpenApiException(
                                "DescribeIssue", "Forbidden", "synthetic denied")),
                        clock.instant()))));
    mirror.request(context, null, List.of(member));
    run();
    assertThat(mirror.issue(context, null, member, remote.get(0))).isNull();
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
  }

  private String key() {
    return mirror.key(context, null, member);
  }

  @Test
  void initializationPublishesNewestMonthFirstAndResumesPersistedHistoryFromJanuaryCutoff() {
    long january = WorklogMirror.INITIAL_FROM;
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    remote.add(
        row(
            2,
            "2026-10-19",
            date("2026-10-19").atStartOfDay(WorklogMirror.ZONE).toInstant().toEpochMilli(),
            4));
    remote.add(row(3, "2026-01-01", january, 2));
    remote.add(row(4, "2025-12-31", january - 1, 9));
    mirror.status(context, null, List.of(member));
    runOne();
    var first = repo.source(key());
    assertThat(first.historyBefore())
        .isEqualTo(date("2026-11-01").atStartOfDay(WorklogMirror.ZONE).toInstant().toEpochMilli());
    assertThat(mirror.total(context, null, List.of(member), date("2026-01-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
    assertThat(mirror.status(context, null, List.of(member)).partial()).isTrue();
    assertThat(mirror.status(context, null, List.of(member)).syncedAt()).isNotBlank();
    var query =
        new WorklogQueryService(
            coding,
            new CodingIssueLinkBuilder(),
            new ZhaogangProperties(),
            deleted,
            mock(WorklogIssueChecks.class),
            mirror);
    try {
      LocalDate day = date("2026-11-19");
      var report =
          query.queryRange(
              context,
              Scope.SELF,
              null,
              List.of(member),
              day,
              day.plusDays(1),
              new Schedule("2026-11", 1, List.of(new Day(day, DayType.WORKDAY, false))),
              Map.of(),
              date("2026-11-20"),
              0,
              false);
      assertThat(report.statistics().coverage().partial()).isTrue();
      assertThat(report.statistics().dailyTotals().get(0).hours()).isEqualByComparingTo("8");
      assertThat(report.absences().members()).isEmpty();
    } finally {
      query.shutdown();
    }
    mirror.shutdown();
    mirror =
        new WorklogMirror(
            repo,
            credentials,
            teams,
            coding,
            loader,
            deleted,
            new ZhaogangProperties(),
            targets,
            clock);
    runOne();
    assertThat(mirror.total(context, null, List.of(member), date("2026-01-01"), date("2026-12-01")))
        .isEqualByComparingTo("12");
    run();
    assertThat(repo.source(key()).historyBefore()).isEqualTo(january);
    assertThat(mirror.total(context, null, List.of(member), date("2025-01-01"), date("2026-12-01")))
        .isEqualByComparingTo("14");
    assertThat(mirror.status(context, null, List.of(member)).partial()).isFalse();
    assertThat(mirror.problem(repo.source(key()), date("2025-12-01"), date("2026-01-01")))
        .isNotNull();
  }

  @Test
  void allMembersRecentDataPrecedesOlderHistoryAndRefreshPreemptsQueuedBackfill() {
    var second = new MemberCredential(202, "第二成员", "", "synthetic-second");
    when(credentials.token(10, 202)).thenReturn(Optional.of(second.token()));
    mirror.status(context, 1L, List.of(member, second));
    runOne();
    // Repeated discovery of an already-initialized member must not starve a new member.
    mirror.scheduledRequest(context, null, List.of(member));
    assertThat(repo.pending(clock.millis()).get(0).userId()).isEqualTo(202);
    runOne();
    long before = repo.source(key()).historyBefore();
    clock.advance(900_000);
    mirror.request(context, null, List.of(member));
    runOne();
    assertThat(repo.source(key()).historyBefore()).isEqualTo(before);
    assertThat(repo.source(key()).incrementAt()).isEqualTo(clock.millis());
    assertThat(repo.source(key()).requestedFrom()).isEqualTo(-2);
  }

  @Test
  void failedHistoryChunkKeepsPublishedRecentDataAndItsPersistedBoundary() {
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    mirror.status(context, null, List.of(member));
    runOne();
    var before = repo.source(key());
    when(coding.worklogPage(anyString(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("synthetic timeout"));
    runOne();
    assertThat(repo.source(key()).historyBefore()).isEqualTo(before.historyBefore());
    assertThat(repo.source(key()).through()).isEqualTo(before.through());
    assertThat(mirror.total(context, null, List.of(member), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
  }

  @Test
  void refreshDuringRunningHistoryFollowsUpWithRecentIncrementBeforeContinuingHistory() {
    mirror.status(context, null, List.of(member));
    runOne();
    var source = repo.source(key());
    assertThat(repo.claim(key(), "history", clock.millis())).isTrue();
    clock.advance(900_000);
    mirror.request(context, null, List.of(member));
    mirror.run(key(), "history");
    var completed = repo.source(key());
    assertThat(completed.historyBefore()).isLessThan(source.historyBefore());
    assertThat(completed.requestedFrom()).isEqualTo(-1);
    assertThat(completed.incrementAt()).isLessThan(clock.millis());
    runOne();
    assertThat(repo.source(key()).incrementAt()).isEqualTo(clock.millis());
    assertThat(repo.source(key()).historyBefore()).isEqualTo(completed.historyBefore());
  }

  @Test
  void managementDataUsesOneCanonicalSourceAcrossTeamsAndPersonalBindingInvalidatesIt() {
    when(credentials.token(10, 101)).thenReturn(Optional.empty());
    when(targets.managementTeamForMember(10, 101)).thenReturn(1L);
    when(teams.findWorklogSyncTeam(10, 1))
        .thenReturn(
            Optional.of(
                new CodeSyncTeam(
                    "测试团队",
                    "fixture",
                    "https://fixture.invalid",
                    List.of(new CodeSyncMember(101, "测试成员", "", null, null, true, false)),
                    new TeamStatisticsCredential("synthetic-management"),
                    999)));
    var managed = new MemberCredential(101, "测试成员", "", "synthetic-other-manager", true);
    mirror.status(context, 2L, List.of(managed));
    mirror.status(context, 1L, List.of(managed));
    remote.add(row(1, "2026-11-19", clock.millis() - 1000, 8));
    run();
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT COUNT(*) FROM external_zhaogang_worklog_source", Long.class))
        .isEqualTo(1);
    assertThat(mirror.total(context, 2L, List.of(managed), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
    assertThat(mirror.total(context, 1L, List.of(managed), date("2026-11-01"), date("2026-12-01")))
        .isEqualByComparingTo("8");
    verify(coding, never())
        .worklogPage(
            eq("synthetic-other-manager"), anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
    when(credentials.token(10, 101)).thenReturn(Optional.of(member.token()));
    mirror.status(context, 2L, List.of(managed));
    assertThat(repo.source(key()).through()).isZero();
    assertThat(mirror.total(context, 1L, List.of(managed), date("2026-11-01"), date("2026-12-01")))
        .isZero();
    assertThat(repo.source(key()).managementTeamId()).isZero();
    assertThat(
            jdbc.getJdbcTemplate()
                .queryForObject(
                    "SELECT COUNT(*) FROM external_zhaogang_worklog_source", Long.class))
        .isEqualTo(1);
  }

  private void runOne() {
    var source = repo.pending(clock.millis()).get(0);
    String lease = UUID.randomUUID().toString();
    assertThat(repo.claim(source.key(), lease, clock.millis())).isTrue();
    mirror.run(source.key(), lease);
  }

  private void run() {
    for (int round = 0; round < 30; round++) {
      var pending = repo.pending(clock.millis());
      if (pending.isEmpty()) return;
      for (var source : pending) {
        String lease = UUID.randomUUID().toString();
        if (repo.claim(source.key(), lease, clock.millis())) mirror.run(source.key(), lease);
      }
    }
    throw new AssertionError("Mirror queue did not drain");
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }

  private Worklog row(long id, String date, long created, int hours) {
    return new Worklog(
        id,
        10,
        1,
        "fixture",
        101,
        BigDecimal.valueOf(hours),
        "synthetic",
        LocalDate.parse(date).atTime(12, 0).atZone(WorklogMirror.ZONE).toInstant().toEpochMilli(),
        created,
        clock.millis());
  }

  private static class MutableClock extends Clock {
    long now = Instant.parse("2026-11-20T06:00:00Z").toEpochMilli();

    void advance(long amount) {
      now += amount;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }
  }
}
