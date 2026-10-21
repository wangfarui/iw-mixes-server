package com.itwray.iw.external.zhaogang.iteration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.itwray.iw.external.mapper.ZhaogangIterationIssueMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationIssueWorklogMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMemberMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationMemberRoleMapper;
import com.itwray.iw.external.mapper.ZhaogangIterationReleasePlanMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IssueEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IterationPrototypePersistenceTest {

    @Test
    void snapshotSqlCannotClobberLocalPrototypeAndEditorCanExplicitlyClearIt() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ZhaogangIterationIssueMapper.class);
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        IssueEntity issue = new IssueEntity();
        issue.setId(2L);
        issue.setIterationId(1L);
        issue.setTitle("需求");
        issue.setPrototypeUrl("https://example.com/stale-snapshot");
        String updateSql = configuration.getMappedStatement(ZhaogangIterationIssueMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", issue)).getSql();
        assertThat(updateSql).contains("title").doesNotContain("prototype_url");

        ZhaogangIterationIssueMapper mapper = mock(ZhaogangIterationIssueMapper.class);
        ZhaogangIterationMapper iterationMapper = mock(ZhaogangIterationMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(issue);
        MybatisTeamIterationRepository repository = new MybatisTeamIterationRepository(iterationMapper,
                mock(ZhaogangIterationMemberMapper.class), mock(ZhaogangIterationMemberRoleMapper.class), mapper,
                mock(ZhaogangIterationIssueWorklogMapper.class), mock(ZhaogangIterationReleasePlanMapper.class));
        var actor = new TeamIterationModels.Actor(100L, "tester", "", "test-token", "team");
        var updated = repository.updateIssue(1L, 2L, new TeamIterationModels.UpdateIssueCommand("需求", "描述",
                null, null, null, null, null, null, null, ""), actor);
        assertThat(updated.getPrototypeUrl()).isNull();
        ArgumentCaptor<LambdaUpdateWrapper<IssueEntity>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet()).contains("prototype_url=");
        assertThat(wrapper.getValue().getSqlSegment()).contains("iteration_id", "id");
        assertThat(wrapper.getValue().getParamNameValuePairs()).containsValue(null).containsValue(1L).containsValue(2L);
        verify(iterationMapper).touch(1L, actor.userId(), actor.userName());
    }
}
