package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CodeStatsModuleContextTest {
    @Test
    void springCreatesModuleWithItsProductionDependencies() {
        new ApplicationContextRunner()
                .withBean(CodeStatsScopeDirectory.class, () -> mock(CodeStatsScopeDirectory.class))
                .withBean(CodingOpenApiPort.class, () -> mock(CodingOpenApiPort.class))
                .withBean(ZhaogangCodeScanJobMapper.class, () -> mock(ZhaogangCodeScanJobMapper.class))
                .withBean(ZhaogangCodeScanRepoMapper.class, () -> mock(ZhaogangCodeScanRepoMapper.class))
                .withBean(ZhaogangCodeCommitMapper.class, () -> mock(ZhaogangCodeCommitMapper.class))
                .withBean(CodeIdentityResolver.class, () -> mock(CodeIdentityResolver.class))
                .withBean(CodeGitLineStats.class)
                .withBean(CodeScanWorker.class)
                .withBean(CodeScanService.class)
                .withBean(CodeStatsQueryService.class, () -> mock(CodeStatsQueryService.class))
                .withBean(CodeIdentityService.class, () -> mock(CodeIdentityService.class))
                .withBean(CodeStatsViewCache.class, () -> mock(CodeStatsViewCache.class))
                .withBean(ZhaogangProperties.class, ZhaogangProperties::new)
                .withBean(DefaultCodeStatsModule.class)
                .run(context -> assertThat(context).hasSingleBean(CodeStatsModule.class));
    }
}
