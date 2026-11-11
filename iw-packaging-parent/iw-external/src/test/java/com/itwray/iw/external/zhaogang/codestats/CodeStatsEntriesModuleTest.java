package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.*;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeStatsEntriesModuleTest {
    private final CodeStatsScopeDirectory directory = mock(CodeStatsScopeDirectory.class);
    private final CodeScanService scans = mock(CodeScanService.class);
    private final CodeStatsQueryService queries = mock(CodeStatsQueryService.class);
    private final CodeStatsViewCache cache = mock(CodeStatsViewCache.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final CodeStatsModule.Context actor = new CodeStatsModule.Context("synthetic", 1, "本人", "", 100, "team", "https://example.com");
    private final DefaultCodeStatsModule module = new DefaultCodeStatsModule(directory, scans, queries,
            mock(CodeIdentityService.class), cache, new ZhaogangProperties(), clock);
    private final CodeStatsModule.ScopeInfo team = new CodeStatsModule.ScopeInfo(Scope.WORKBENCH_TEAM, 9, 100,
            "team", "https://example.com", "团队", List.of(
            new CodeStatsModule.MemberCredential(1, "甲", "", "synthetic", CodingRole.BACKEND),
            new CodeStatsModule.MemberCredential(2, "乙", "", "synthetic", CodingRole.FRONTEND)), false);

    @Test
    void cacheSeparatesMemberFilterAndSortAndReusesIdenticalQuery() {
        when(directory.resolve(actor, Scope.WORKBENCH_TEAM, 9L)).thenReturn(team);
        when(cache.scopeKey(team)).thenReturn("100:WORKBENCH_TEAM:9");
        when(scans.jobMapper()).thenReturn(mock(ZhaogangCodeScanJobMapper.class));
        CodeRange range = CodeRange.parse("2026-10-01", "2026-10-30", 24, clock);
        ScanState state = new ScanState("WORKBENCH_TEAM", 9L, range.view(), "COMPLETED", List.of(), 0);
        when(scans.scanState(eq(team), any())).thenReturn(state);
        when(scans.dataReady(state)).thenReturn(true);
        EntryPage result = new EntryPage(range.view(), new Page(1, 20, 0, 0), List.of(), List.of());
        when(queries.entries(eq(team), any(), anyLong(), nullable(Long.class), nullable(String.class),
                anyInt(), anyInt(), any(), any())).thenReturn(result);
        Map<String, EntryPage> stored = new HashMap<>();
        when(cache.get(anyString(), eq(EntryPage.class))).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(cache).put(anyString(), any());
        assertSame(result, entries(1L, null, null, EntrySort.COMMIT_AT, SortDirection.DESC));
        assertSame(result, entries(1L, null, null, EntrySort.COMMIT_AT, SortDirection.DESC));
        entries(2L, null, null, EntrySort.COMMIT_AT, SortDirection.DESC);
        entries(1L, null, null, EntrySort.ADDITIONS, SortDirection.DESC);
        entries(1L, null, null, EntrySort.ADDITIONS, SortDirection.ASC);
        entries(1L, 20L, "fix", EntrySort.COMMIT_AT, SortDirection.DESC);
        assertEquals(5, stored.size());
        verify(queries, times(5)).entries(eq(team), any(), anyLong(), nullable(Long.class), nullable(String.class),
                anyInt(), anyInt(), any(), any());
    }

    @Test
    void rejectsNonMemberBeforeReadingCache() {
        when(directory.resolve(actor, Scope.WORKBENCH_TEAM, 9L))
                .thenThrow(new CodeStatsForbiddenException("你不是该工作台团队成员"));
        assertThrows(CodeStatsForbiddenException.class,
                () -> entries(1L, null, null, EntrySort.COMMIT_AT, SortDirection.DESC));
        verifyNoInteractions(cache, queries);
    }

    @Test
    void rejectsInvalidTargetBeforeReadingCache() {
        when(directory.resolve(actor, Scope.WORKBENCH_TEAM, 9L)).thenReturn(team);
        for (Long member : new Long[]{null, 999L}) {
            assertThrows(CodingOpenApiException.class,
                    () -> entries(member, null, null, EntrySort.COMMIT_AT, SortDirection.DESC));
        }
        verifyNoInteractions(cache, queries);
    }

    private EntryPage entries(Long member, Long depot, String keyword, EntrySort sort, SortDirection direction) {
        return module.entries(actor, "2026-10-01", "2026-10-30", "WORKBENCH_TEAM", 9L, member,
                depot, keyword, 1, 20, sort, direction, false);
    }
}
