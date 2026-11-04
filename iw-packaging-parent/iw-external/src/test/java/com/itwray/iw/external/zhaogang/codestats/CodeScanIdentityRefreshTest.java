package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeScanIdentityRefreshTest {
    final ZhaogangCodeScanJobMapper jobs=mock(ZhaogangCodeScanJobMapper.class);
    final CodeIdentityResolver identities=mock(CodeIdentityResolver.class);
    final CodeScanWorker worker=mock(CodeScanWorker.class);
    final CodeStatsModule.ScopeInfo scope=new CodeStatsModule.ScopeInfo(CodeStatsModels.Scope.SELF,1,100,"t","https://example.com","t",
            List.of(new CodeStatsModule.MemberCredential(1,"Self","","fixture")),false);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"),CodeStatsSupport.CHINA_ZONE);
    final CodeRange range=new CodeRange(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),List.of(YearMonth.of(2026,10)));

    @Test
    void queryCannotStartHistoricalScanEvenWhenIdentityChanges() {
        var last=last("old");when(jobs.selectLatest(anyLong(),anyString(),anyLong(),anyString())).thenReturn(last);
        when(identities.signature(scope)).thenReturn("new");
        try(var harness=new Harness()) {
            harness.service.autoRefresh(scope,range,1);
            verify(jobs,never()).insert(any(ScanJobEntity.class));
            verifyNoInteractions(worker);
        }
    }

    @Test
    void unchangedIdentityHonorsHistoricalCooldownAndActiveJobCannotDuplicate() {
        var last=last("same");when(jobs.selectLatest(anyLong(),anyString(),anyLong(),anyString())).thenReturn(last);
        when(identities.signature(scope)).thenReturn("same");
        try(var harness=new Harness()) {
            harness.service.autoRefresh(scope,range,1);
            last.setStatus("RUNNING");last.setIdentitySignature("old");
            harness.service.autoRefresh(scope,range,1);
            verify(jobs,never()).insert(any(ScanJobEntity.class));
        }
    }

    @Test
    void queryCannotUpgradeLegacyDataByScanning() {
        when(jobs.selectLatest(anyLong(),anyString(),anyLong(),anyString())).thenReturn(last(null));
        when(identities.signature(scope)).thenReturn("identity-v2");
        try(var harness=new Harness()) {
            harness.service.autoRefresh(scope,range,1);
            verify(jobs,never()).insert(any(ScanJobEntity.class));
        }
    }

    ScanJobEntity last(String signature) {
        var row=new ScanJobEntity();row.setId(9L);row.setStatus("COMPLETED");row.setIdentitySignature(signature);
        row.setFinishedAt(LocalDateTime.now(clock));return row;
    }
    class Harness implements AutoCloseable {
        final CodeScanService service=new CodeScanService(jobs,mock(ZhaogangCodeScanRepoMapper.class),mock(ZhaogangCodeCommitMapper.class),
                mock(CodeStatsViewCache.class),worker,new ZhaogangProperties(),identities,clock);
        public void close(){service.shutdown();}
    }
}
