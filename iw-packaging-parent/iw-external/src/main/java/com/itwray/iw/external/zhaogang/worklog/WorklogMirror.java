package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.mapper.ZhaogangWorklogSyncTargetMapper;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.worklog.WorklogMirrorRepository.Source;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.*;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.Context;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.MemberCredential;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Page reads never call CODING. Tasks resolve credentials afresh, and publish with a fenced lease.
 */
@Component
class WorklogMirror {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  static final long INITIAL_FROM =
      LocalDate.of(2026, 1, 1).atStartOfDay(ZONE).toInstant().toEpochMilli();
  private final WorklogMirrorRepository repo;
  private final CodingCredentialService credentials;
  private final WorkbenchTeamModule teams;
  private final CodingOpenApiPort coding;
  private final CodingIssueSnapshotLoader issues;
  private final WorklogDeletedIssues deleted;
  private final ZhaogangProperties properties;
  private final Clock clock;
  private final ZhaogangWorklogSyncTargetMapper targets;
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          2,
          r -> {
            var t = new Thread(r, "worklog-mirror");
            t.setDaemon(true);
            return t;
          });
  private final AtomicInteger active = new AtomicInteger();
  private final ConcurrentMap<String, String> leases = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  WorklogMirror(
      WorklogMirrorRepository repo,
      CodingCredentialService credentials,
      WorkbenchTeamModule teams,
      CodingOpenApiPort coding,
      CodingIssueSnapshotLoader issues,
      WorklogDeletedIssues deleted,
      ZhaogangProperties properties,
      ZhaogangWorklogSyncTargetMapper targets) {
    this(repo, credentials, teams, coding, issues, deleted, properties, targets, Clock.systemUTC());
  }

  WorklogMirror(
      WorklogMirrorRepository repo,
      CodingCredentialService credentials,
      WorkbenchTeamModule teams,
      CodingOpenApiPort coding,
      CodingIssueSnapshotLoader issues,
      WorklogDeletedIssues deleted,
      ZhaogangProperties properties,
      ZhaogangWorklogSyncTargetMapper targets,
      Clock clock) {
    this.repo = repo;
    this.credentials = credentials;
    this.teams = teams;
    this.coding = coding;
    this.issues = issues;
    this.deleted = deleted;
    this.properties = properties;
    this.clock = clock;
    this.targets = targets;
  }

  String key(Context context, Long teamId, MemberCredential member) {
    return memberKey(context.codingTeamId(), member.userId());
  }

  private String memberKey(long codingTeamId, long userId) {
    return CodingCredentialService.fingerprint("MEMBER:" + codingTeamId + ":" + userId);
  }

  private record Credential(long managementTeamId, String token) {}

  private Credential credential(long codingTeamId, long userId) {
    var personal = credentials.token(codingTeamId, userId);
    if (personal.isPresent()) return new Credential(0, personal.get());
    Long selectedTeam = targets.managementTeamForMember(codingTeamId, userId);
    if (selectedTeam == null) return new Credential(0, "");
    return teams
        .findWorklogSyncTeam(codingTeamId, selectedTeam)
        .filter(
            t ->
                t.members().stream()
                    .anyMatch(m -> m.userId() == userId && !m.ignoreTeamStatistics()))
        .map(t -> new Credential(selectedTeam, t.managementCredential().token()))
        .orElse(new Credential(0, ""));
  }

  List<String> keys(Context context, Long team, List<MemberCredential> members) {
    return members.stream().map(m -> key(context, team, m)).distinct().toList();
  }

  List<Source> ensure(Context context, Long team, List<MemberCredential> members) {
    for (var member :
        members.stream().sorted(Comparator.comparing(m -> key(context, team, m))).toList()) {
      var selected = credential(context.codingTeamId(), member.userId());
      if (selected.token().isBlank()) {
        repo.unavailable(key(context, team, member));
        continue;
      }
      repo.ensure(
          key(context, team, member),
          context.codingTeamId(),
          member.userId(),
          selected.managementTeamId(),
          CodingCredentialService.fingerprint(selected.token()));
    }
    return repo.sources(keys(context, team, members));
  }

  String version(Context context, Long team, List<MemberCredential> members) {
    return CodingCredentialService.fingerprint(
        ensure(context, team, members).stream()
            .sorted(Comparator.comparing(Source::key))
            .map(
                s ->
                    s.key()
                        + ":"
                        + s.version()
                        + ":"
                        + (s.state().equals("FAILED") ? "FAILED" : "AVAILABLE")
                        + ":"
                        + s.blocked()
                        + ":"
                        + (clock.millis() - s.incrementAt() > 1_800_000))
            .collect(Collectors.joining("|")));
  }

  SyncStatus status(Context context, Long team, List<MemberCredential> members) {
    var sources = ensure(context, team, members);
    var byKey = sources.stream().collect(Collectors.toMap(Source::key, s -> s));
    String scopeKey = scopeKey(context, team, members);
    var times = repo.scope(scopeKey, clock.millis(), false);
    long requestedAt = times.requestedAt();
    boolean running =
        sources.stream().anyMatch(s -> s.state().equals("QUEUED") || s.state().equals("RUNNING"));
    boolean failed =
        sources.size() != members.size()
            || sources.stream()
                .anyMatch(s -> s.state().equals("FAILED") || s.blocked() || s.incomplete());
    long completed = sources.stream().mapToLong(Source::incrementAt).max().orElse(0);
    if (!members.isEmpty()
        && !failed
        && sources.stream().allMatch(s -> s.incrementAt() >= requestedAt)
        && completed > times.syncedAt()) {
      repo.completeScope(scopeKey, times.requestedAt(), completed);
      times = new WorklogMirrorRepository.ScopeTimes(times.requestedAt(), completed);
    }
    if (times.syncedAt() == 0
        && !members.isEmpty()
        && !failed
        && sources.stream().allMatch(s -> s.incrementAt() > 0)) {
      long covered = sources.stream().mapToLong(Source::incrementAt).min().orElse(0);
      repo.completeScope(scopeKey, times.requestedAt(), covered);
      times = new WorklogMirrorRepository.ScopeTimes(times.requestedAt(), covered);
    }
    List<SyncMember> results =
        members.stream()
            .map(
                m -> {
                  Source s = byKey.get(key(context, team, m));
                  return new SyncMember(
                      new User(m.userId(), m.userName(), m.avatar()),
                      s == null || s.state().equals("MISSING")
                          ? "NONE"
                          : s.managementTeamId() > 0 ? "TEAM_MANAGED" : "PERSONAL",
                      s == null ? "MISSING" : s.state(),
                      s == null ? "" : time(s.incrementAt()),
                      s == null
                          ? "未绑定个人令牌且关联团队管理令牌不可用"
                          : s.historyBefore() > INITIAL_FROM && s.warning().isBlank()
                              ? "近期数据已同步，正在从近到远补齐历史工时"
                              : s.warning());
                })
            .toList();
    return new SyncStatus(
        running,
        time(times.syncedAt()),
        version(context, team, members),
        failed
            || sources.stream()
                .anyMatch(
                    s -> s.incomplete() || s.through() == 0 || s.historyBefore() > INITIAL_FROM),
        results);
  }

  SyncStatus request(Context context, Long team, List<MemberCredential> members) {
    return request(context, team, members, 5);
  }

  SyncStatus scheduledRequest(Context context, Long team, List<MemberCredential> members) {
    return request(context, team, members, 10);
  }

  private SyncStatus request(
      Context context, Long team, List<MemberCredential> members, int priority) {
    var before = status(context, team, members);
    if (before.running()) {
      repo.scope(scopeKey(context, team, members), clock.millis(), true);
      for (var key : keys(context, team, members)) {
        repo.incrementalRequest(key, priority);
      }
      return before;
    }
    // Repeated clicks within 10 seconds share the successful round.
    long latest =
        ensure(context, team, members).stream().mapToLong(Source::incrementAt).min().orElse(0);
    if (latest > 0 && clock.millis() - latest < 10_000) return before;
    repo.scope(scopeKey(context, team, members), clock.millis(), true);
    for (var key : keys(context, team, members)) repo.incrementalRequest(key, priority);
    return status(context, team, members);
  }

  void reconcile(Context context, Long team, List<MemberCredential> members, long from) {
    for (var s : ensure(context, team, members))
      if (s.through() > 0) repo.queue(s.key(), from, false, 20);
  }

  void afterRegistration(Context context, MemberCredential member) {
    ensure(context, null, List.of(member));
    repo.scope(scopeKey(context, null, List.of(member)), clock.millis(), true);
    String key = key(context, null, member);
    repo.incrementalRequest(key, 0);
    // If the task already fixed its upper bound before the write, one durable follow-up is
    // required.
    repo.requestRerun(key);
  }

  Source source(Context context, Long team, MemberCredential member) {
    return ensure(context, team, List.of(member)).stream().findFirst().orElse(null);
  }

  List<Worklog> rows(
      Context context,
      Long team,
      List<MemberCredential> members,
      LocalDate from,
      LocalDate to,
      WorklogQueryService.EntryPosition after,
      int limit) {
    ensure(context, team, members);
    return repo.rows(keys(context, team, members), millis(from), millis(to), after, limit);
  }

  java.math.BigDecimal total(
      Context context, Long team, List<MemberCredential> members, LocalDate from, LocalDate to) {
    return repo.total(keys(context, team, members), millis(from), millis(to));
  }

  Issue issue(Context context, Long team, MemberCredential member, Worklog row) {
    return repo.issue(key(context, team, member), row.projectName(), row.issueCode());
  }

  String syncedAt(Context context, Long team, List<MemberCredential> members) {
    return status(context, team, members).syncedAt();
  }

  ReadProblem problem(Source s) {
    if (s == null) return new ReadProblem("TOKEN_MISSING", "未绑定个人令牌且团队管理令牌不可用");
    if (s.blocked()) return new ReadProblem("PERMISSION_DENIED", s.warning());
    if (s.through() == 0)
      return new ReadProblem("NOT_SYNCED", s.warning().isBlank() ? "工时正在初始化，请稍后刷新" : s.warning());
    if (s.state().equals("FAILED")) return new ReadProblem("SYNC_FAILED", s.warning());
    if (s.incomplete()) return new ReadProblem("INCOMPLETE", s.warning());
    if (s.historyBefore() > INITIAL_FROM)
      return new ReadProblem("INITIALIZING", "近期数据已同步，正在从近到远补齐 2026 年以来的历史工时，暂不据此判断缺勤");
    if (clock.millis() - s.incrementAt() > Duration.ofMinutes(30).toMillis())
      return new ReadProblem("STALE", "工时数据尚未更新，暂不据此判断缺勤");
    return null;
  }

  ReadProblem problem(Source s, LocalDate from, LocalDate to) {
    ReadProblem existing = problem(s);
    if (existing != null) return existing;
    if (millis(from) < INITIAL_FROM)
      return new ReadProblem("OUTSIDE_COVERAGE", "工时镜像从 2026-01-01 起采集，更早日期的数据不完整");
    return repo.missing(s.key(), millis(from), millis(to)) > 0
        ? new ReadProblem("INCOMPLETE", "本次日期范围有工时在当前令牌下已不再可见，暂不据此判断缺勤")
        : null;
  }

  int projectCount(
      Context context, Long team, List<MemberCredential> members, LocalDate from, LocalDate to) {
    return repo.projectCount(keys(context, team, members), millis(from), millis(to));
  }

  @Scheduled(fixedDelay = 2000)
  void dispatch() {
    leases.forEach((key, lease) -> repo.heartbeat(key, lease, clock.millis()));
    if (!properties.isWorklogSyncEnabled()) return;
    for (var source : repo.pending(clock.millis())) {
      if (active.get() >= 2) break;
      String lease = UUID.randomUUID().toString();
      if (!repo.claim(source.key(), lease, clock.millis())) continue;
      leases.put(source.key(), lease);
      active.incrementAndGet();
      workers.execute(
          () -> {
            try {
              run(source.key(), lease);
            } finally {
              leases.remove(source.key(), lease);
              active.decrementAndGet();
            }
          });
    }
  }

  void run(String sourceKey, String lease) {
    Source source = repo.source(sourceKey);
    try {
      String token = resolveToken(source);
      boolean initial = source.through() == 0;
      boolean history = !initial && source.requestedFrom() == -2;
      long through = history ? source.historyBefore() - 1 : clock.millis();
      long from =
          initial || history
              ? Math.max(
                  INITIAL_FROM,
                  Instant.ofEpochMilli(through)
                      .atZone(ZONE)
                      .withDayOfMonth(1)
                      .toLocalDate()
                      .atStartOfDay(ZONE)
                      .toInstant()
                      .toEpochMilli())
              : source.requestedFrom() >= 0
                  ? Math.max(INITIAL_FROM, source.requestedFrom())
                  : Math.max(INITIAL_FROM, source.through() - 600_000);
      var rows = fetch(source, lease, token, from, through);
      boolean reconcile = source.requestedFrom() >= 0 && source.through() > 0;
      // A changing offset result must not invalidate previously observed records.
      if ((reconcile || initial || history)
          && !rows.equals(fetch(source, lease, token, from, through)))
        throw new ReadProblem("UNSTABLE", "CODING 工时分页结果发生变化，将在下次同步重试");
      Map<IssueKey, Issue> snapshots = new LinkedHashMap<>();
      Map<Long, IssueKey> missingIssues = new LinkedHashMap<>();
      List<IssueKey> keys =
          rows.values().stream()
              .filter(w -> w.issueCode() > 0 && !w.projectName().isBlank())
              .map(w -> new IssueKey(w.projectName(), w.issueCode()))
              .distinct()
              .toList();
      // Small batches renew the task lease while the shared limiter waits.
      for (int i = 0; i < keys.size(); i += 32) {
        repo.heartbeat(sourceKey, lease, clock.millis());
        var batch = keys.subList(i, Math.min(keys.size(), i + 32));
        var loaded =
            issues.loadForWorklogs(Map.of(token, batch), false).getOrDefault(token, Map.of());
        loaded.forEach(
            (key, checked) -> {
              if (checked.lookup().issue() != null) snapshots.put(key, checked.lookup().issue());
              else if (checked.lookup().error() instanceof CodingOpenApiException error
                  && error.isPermissionDenied()) snapshots.put(key, null);
              else if (checked.lookup().missing()) {
                var ids =
                    rows.values().stream()
                        .filter(
                            w ->
                                key.projectName().equals(w.projectName())
                                    && key.issueCode() == w.issueCode())
                        .map(Worklog::issueId)
                        .filter(id -> id > 0)
                        .distinct()
                        .toList();
                if (ids.size() == 1) missingIssues.put(ids.get(0), key);
              }
            });
      }
      snapshots.values().stream()
          .filter(Objects::nonNull)
          .forEach(issue -> missingIssues.remove(issue.id()));
      // Recheck after remote I/O: a changed credential or membership cannot publish an old source.
      if (!token.equals(resolveToken(source)))
        throw new ReadProblem("SOURCE_CHANGED", "工时统计令牌已变化，请重新同步");
      deleted.record(source.teamId(), missingIssues);
      boolean incomplete =
          rows.values().stream().anyMatch(w -> w.startAt() == null || w.startAt() <= 0);
      repo.publish(
          source,
          lease,
          from,
          through,
          clock.millis(),
          new ArrayList<>(rows.values()),
          snapshots,
          reconcile,
          incomplete);
    } catch (Exception failure) {
      boolean denied =
          failure instanceof CodingOpenApiException c
              && (c.isPermissionDenied()
                  || Objects.equals(c.httpStatus(), 401)
                  || Objects.equals(c.httpStatus(), 403)
                  || c.code().toLowerCase(Locale.ROOT).matches(".*(auth|token|credential).*"));
      String warning =
          failure instanceof ReadProblem p
              ? p.getMessage()
              : denied
                  ? (source.managementTeamId() == 0
                      ? "个人令牌已失效或权限不足，请更新个人 PAT；不会自动切换团队令牌"
                      : "团队管理令牌已失效或权限不足，请检查团队配置")
                  : "工时同步失败，请稍后重试";
      repo.fail(
          sourceKey,
          lease,
          denied || failure instanceof ReadProblem p && p.code.equals("SOURCE_CHANGED"),
          warning,
          clock.millis());
    }
  }

  private LinkedHashMap<Long, Worklog> fetch(
      Source s, String lease, String token, long from, long through) {
    LinkedHashMap<Long, Worklog> rows = new LinkedHashMap<>();
    for (int offset = 0; offset < 100_000; offset += 1000) {
      repo.heartbeat(s.key(), lease, clock.millis());
      var page =
          coding
              .worklogPage(token, Math.max(0, from - 1), through + 1, s.userId(), offset, 1000)
              .items();
      for (var row : page) {
        if (row.id() <= 0
            || row.userId() != s.userId()
            || row.createdAt() == null
            || row.createdAt() <= 0
            || row.projectName() == null)
          throw new ReadProblem("INVALID_ROW", "CODING 返回的工时身份或提交时间不完整，未发布本批结果");
        if (row.createdAt() < from || row.createdAt() > through) continue;
        Worklog previous = rows.put(row.id(), row);
        if (previous != null && !previous.equals(row))
          throw new ReadProblem("UNSTABLE", "CODING 工时分页结果发生变化，请重试");
      }
      if (page.size() < 1000) return rows;
    }
    throw new ReadProblem("PAGE_LIMIT", "工时记录达到查询页数上限，本批结果未发布");
  }

  private String resolveToken(Source s) {
    var selected = credential(s.teamId(), s.userId());
    if (!s.key().equals(memberKey(s.teamId(), s.userId()))
        || selected.token().isBlank()
        || selected.managementTeamId() != s.managementTeamId()
        || !CodingCredentialService.fingerprint(selected.token()).equals(s.fingerprint()))
      throw new ReadProblem("SOURCE_CHANGED", "成员或统计令牌已变化，请重新同步");
    return selected.token();
  }

  private String scopeKey(Context c, Long team, List<MemberCredential> members) {
    return CodingCredentialService.fingerprint(
        c.codingTeamId()
            + ":"
            + (team == null ? "SELF:" + c.userId() : "TEAM:" + team)
            + ":"
            + repo.sources(keys(c, team, members)).stream()
                .sorted(Comparator.comparing(Source::key))
                .map(s -> s.key() + ":" + s.fingerprint() + ":" + s.managementTeamId())
                .collect(Collectors.joining(",")));
  }

  private static long millis(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant().toEpochMilli();
  }

  private static String time(long millis) {
    return millis <= 0 ? "" : Instant.ofEpochMilli(millis).toString();
  }

  @PreDestroy
  void shutdown() {
    workers.shutdownNow();
  }

  static class ReadProblem extends RuntimeException {
    final String code;

    ReadProblem(String code, String message) {
      super(message);
      this.code = code;
    }
  }
}
