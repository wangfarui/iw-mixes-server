package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.mapper.ZhaogangWorklogSyncTargetMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.*;
import java.time.*;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WorklogMirrorScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(WorklogMirrorScheduler.class);
  private final ZhaogangWorklogSyncTargetMapper targets;
  private final CodingCredentialService credentials;
  private final WorkbenchTeamModule teams;
  private final WorklogMirror mirror;
  private final ZhaogangProperties properties;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  WorklogMirrorScheduler(
      ZhaogangWorklogSyncTargetMapper targets,
      CodingCredentialService credentials,
      WorkbenchTeamModule teams,
      WorklogMirror mirror,
      ZhaogangProperties properties) {
    this(targets, credentials, teams, mirror, properties, Clock.systemUTC());
  }

  WorklogMirrorScheduler(
      ZhaogangWorklogSyncTargetMapper targets,
      CodingCredentialService credentials,
      WorkbenchTeamModule teams,
      WorklogMirror mirror,
      ZhaogangProperties properties,
      Clock clock) {
    this.targets = targets;
    this.credentials = credentials;
    this.teams = teams;
    this.mirror = mirror;
    this.properties = properties;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  void startup() {
    incremental();
  }

  @EventListener
  void registered(WorklogRegistered event) {
    if (!properties.isWorklogSyncEnabled()) return;
    try {
      credentials
          .token(event.codingTeamId(), event.codingUserId())
          .ifPresent(
              token -> {
                var context =
                    new Context(
                        "",
                        event.codingUserId(),
                        "",
                        "",
                        event.codingTeamId(),
                        properties.getTeam(),
                        properties.configuredTeamHost());
                mirror.afterRegistration(
                    context, new MemberCredential(event.codingUserId(), "", "", token));
              });
    } catch (RuntimeException error) {
      // CODING has accepted the registration. A local scheduling failure must never turn it into a
      // failed write.
      LOG.warn("登记成功后的工时采集调度失败，error={}", error.getClass().getSimpleName());
    }
  }

  @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Shanghai")
  void incremental() {
    schedule(-1);
  }

  @Scheduled(cron = "0 7 * * * *", zone = "Asia/Shanghai")
  void recent() {
    schedule(clock.instant().minus(Duration.ofDays(7)).toEpochMilli());
  }

  @Scheduled(cron = "0 25 2 * * *", zone = "Asia/Shanghai")
  void historical() {
    schedule(clock.instant().atZone(WorklogMirror.ZONE).minusMonths(1).toInstant().toEpochMilli());
  }

  private void schedule(long from) {
    if (!properties.isWorklogSyncEnabled()) return;
    try {
      for (var target : targets.targets()) {
        try {
          var context =
              new Context(
                  "",
                  target.userId(),
                  "",
                  "",
                  target.codingTeamId(),
                  properties.getTeam(),
                  properties.configuredTeamHost());
          Long teamId = target.workbenchTeamId() == 0 ? null : target.workbenchTeamId();
          List<MemberCredential> members;
          if (teamId == null)
            members =
                List.of(
                    new MemberCredential(
                        target.userId(),
                        "",
                        "",
                        credentials.token(target.codingTeamId(), target.userId()).orElse("")));
          else {
            var team = teams.findWorklogSyncTeam(target.codingTeamId(), teamId);
            if (team.isEmpty()) continue;
            members =
                WorklogScopeDirectory.preferredMemberCredentials(
                    credentials,
                    target.codingTeamId(),
                    team.get().members().stream().filter(m -> !m.ignoreTeamStatistics()).toList(),
                    team.get().managementCredential());
          }
          if (from < 0) mirror.scheduledRequest(context, teamId, members);
          else mirror.reconcile(context, teamId, members, from);
        } catch (RuntimeException e) {
          LOG.warn("工时自动同步目标调度失败，error={}", e.getClass().getSimpleName());
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("工时自动同步目标发现失败，error={}", e.getClass().getSimpleName());
    }
  }
}
