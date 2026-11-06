package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.DiffRetryRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.DiffRetryTarget;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodeDiffRetryServiceTest {

    private final ZhaogangCodeCommitMapper commits = mock(ZhaogangCodeCommitMapper.class);
    private final ZhaogangCodeScanJobMapper jobs = mock(ZhaogangCodeScanJobMapper.class);
    private final CodeScanWorker worker = mock(CodeScanWorker.class);
    private final CodeScanService service = new CodeScanService(jobs, mock(ZhaogangCodeScanRepoMapper.class),
            commits, mock(CodeStatsViewCache.class), worker, new ZhaogangProperties(), mock(CodeIdentityResolver.class));
    private final LocalDate from = LocalDate.of(2026, 1, 1);
    private final LocalDate to = LocalDate.of(2026, 11, 5);
    private final String sha = "a".repeat(40);
    private final DiffRetryRequest selected = new DiffRetryRequest(List.of(new DiffRetryTarget(100L, sha)));

    @AfterEach
    void stopQueue() {
        service.shutdown();
    }

    @Test
    void sameShaInDifferentRepositoriesQueuesOnlySelectedFailure() {
        when(commits.selectDiffFailures(10L, "WORKBENCH_TEAM", 2L, from, to))
                .thenReturn(List.of(commit(4L, 99L), commit(5L, 100L)));
        when(jobs.insert(any(ScanJobEntity.class))).thenAnswer(call -> {
            call.<ScanJobEntity>getArgument(0).setId(6L);
            return 1;
        });
        var result = service.retryDiffs(scope(true), source(), selected, 3L);
        assertEquals(6L, result.jobId());
        assertEquals(1, result.total());
        var input = ArgumentCaptor.forClass(CodeScanWorker.ScanInput.class);
        verify(worker, timeout(1000)).run(input.capture());
        assertEquals(List.of(5L), input.getValue().retryCommitIds());
        assertEquals(CodeScanWorker.DIFF_RETRY, input.getValue().mode());
        assertEquals("10:WORKBENCH_TEAM:2", input.getValue().job().getActiveKey());
    }

    @Test
    void memberCannotRetryTeamDiffAndMissingTargetsCannotEnqueue() {
        assertThrows(CodeStatsForbiddenException.class,
                () -> service.retryDiffs(scope(false), source(), selected, 3L));
        verifyNoInteractions(commits, jobs, worker);

        when(commits.selectDiffFailures(10L, "WORKBENCH_TEAM", 2L, from, to))
                .thenReturn(List.of(commit(4L, 99L)));
        assertThrows(CodingOpenApiException.class,
                () -> service.retryDiffs(scope(true), source(), selected, 3L));
        verify(jobs, never()).insert(any(ScanJobEntity.class));
        verifyNoInteractions(worker);
    }

    private CodeStatsModule.ScopeInfo scope(boolean admin) {
        return new CodeStatsModule.ScopeInfo(Scope.WORKBENCH_TEAM, 2L, 10L, "fixture", "example.invalid",
                "fixture", List.of(new CodeStatsModule.MemberCredential(3L, "fixture", "", "fixture-token")), admin);
    }

    private ScanJobEntity source() {
        var job = new ScanJobEntity();
        job.setMonth("2026-11"); job.setScanMode("SYNC_V2");
        job.setSyncFrom(from); job.setSyncTo(to);
        job.setFinishedAt(LocalDateTime.of(2026, 11, 5, 9, 3));
        return job;
    }

    private CommitEntity commit(long id, long depotId) {
        var commit = new CommitEntity();
        commit.setId(id); commit.setDepotId(depotId); commit.setSha(sha);
        commit.setCommitDate(LocalDate.of(2026, 10, 29));
        return commit;
    }
}
