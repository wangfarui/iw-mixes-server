package com.itwray.iw.external.zhaogang.worklog;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.itwray.iw.external.mapper.ZhaogangWorklogSyncTargetMapper;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorklogMirrorSchedulerTest {
  private final ZhaogangWorklogSyncTargetMapper targets =
      mock(ZhaogangWorklogSyncTargetMapper.class);
  private final CodingCredentialService credentials = mock(CodingCredentialService.class);
  private final WorklogMirror mirror = mock(WorklogMirror.class);

  private WorklogMirrorScheduler scheduler(String now) {
    when(targets.targets())
        .thenReturn(List.of(new ZhaogangWorklogSyncTargetMapper.Target(10, 101, 0)));
    when(credentials.token(10, 101)).thenReturn(Optional.of("synthetic-scheduler"));
    return new WorklogMirrorScheduler(
        targets,
        credentials,
        mock(WorkbenchTeamModule.class),
        mirror,
        new ZhaogangProperties(),
        Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
  }

  @Test
  void hourlyReconciliationOnlyScansThePreviousSevenDays() {
    scheduler("2026-11-20T02:07:00Z").recent();
    verify(mirror)
        .reconcile(
            any(), isNull(), anyList(), eq(Instant.parse("2026-11-13T02:07:00Z").toEpochMilli()));
    verify(mirror, never()).scheduledRequest(any(), any(), anyList());
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-20T18:25:00Z,2026-10-20T18:25:00Z",
    "2026-11-30T18:25:00Z,2026-10-31T18:25:00Z",
    "2027-03-30T18:25:00Z,2027-02-27T18:25:00Z",
    "2026-01-19T18:25:00Z,2025-12-19T18:25:00Z"
  })
  void nightlyReconciliationUsesOneRollingCalendarMonthInBeijing(String now, String from) {
    scheduler(now).historical();
    verify(mirror).reconcile(any(), isNull(), anyList(), eq(Instant.parse(from).toEpochMilli()));
    verify(mirror, never()).reconcile(any(), any(), anyList(), eq(0L));
  }
}
