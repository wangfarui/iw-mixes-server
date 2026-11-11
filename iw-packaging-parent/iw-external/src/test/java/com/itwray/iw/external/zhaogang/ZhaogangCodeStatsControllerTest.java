package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.EntrySort;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.SortDirection;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZhaogangCodeStatsControllerTest {
    private static final String ENTRIES = "/external-service/api/zhaogang/code-stats/entries";
    private final CodeStatsModule module = mock(CodeStatsModule.class);
    private MockMvc mvc;
    private CodeStatsModule.Context actor;

    @BeforeEach
    void setUp() {
        var sessions = mock(ZhaogangSessionManager.class);
        when(sessions.resolve(any(), any())).thenReturn(new ZhaogangSession("synthetic", 1L, "本人", "", "team", 100L));
        var properties = new ZhaogangProperties();
        actor = new CodeStatsModule.Context("synthetic", 1, "本人", "", 100, "team", properties.configuredTeamHost());
        mvc = MockMvcBuilders.standaloneSetup(new ZhaogangCodeStatsController(sessions, module, properties)).build();
    }

    @Test
    void existingPersonalRequestsKeepDefaults() throws Exception {
        mvc.perform(get(ENTRIES).param("from", "2026-11-01").param("to", "2026-11-11"))
                .andExpect(status().isOk());
        verify(module).entries(actor, "2026-11-01", "2026-11-11", "SELF", null, null,
                null, null, 1, 20, EntrySort.COMMIT_AT, SortDirection.DESC, false);
    }

    @Test
    void bindsTeamMemberRepositoryKeywordPagingAndSingleSort() throws Exception {
        mvc.perform(get(ENTRIES).param("from", "2026-11-01").param("to", "2026-11-11")
                .param("scope", "WORKBENCH_TEAM").param("workbenchTeamId", "9").param("memberUserId", "2")
                .param("depotId", "88").param("keyword", "修复").param("pageNumber", "2").param("pageSize", "10")
                .param("sortBy", "FILES_CHANGED").param("sortOrder", "ASC").param("refresh", "true"))
                .andExpect(status().isOk());
        verify(module).entries(actor, "2026-11-01", "2026-11-11", "WORKBENCH_TEAM", 9L, 2L,
                88L, "修复", 2, 10, EntrySort.FILES_CHANGED, SortDirection.ASC, true);
    }

    @ParameterizedTest
    @CsvSource({"sortBy,message", "sortOrder,sideways"})
    void rejectsUnsupportedSortParameters(String name, String value) throws Exception {
        mvc.perform(get(ENTRIES).param("from", "2026-11-01").param("to", "2026-11-11").param(name, value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(module);
    }
}
