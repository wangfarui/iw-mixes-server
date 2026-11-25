package com.itwray.iw.external.zhaogang.iteration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.worklog.WorklogMutationJournal;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Only explicitly enabled isolated MySQL is accepted. No credentials or production data are used. */
@EnabledIfEnvironmentVariable(named="IW_ITERATION_DELETE_TEST_JDBC_URL", matches=".+")
class IterationWorklogPersistenceTest {
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate tx;
    private MybatisTeamIterationRepository repo;
    private SqlSessionTemplate session;
    private final TeamIterationModels.Actor actor = new TeamIterationModels.Actor(101, "测试成员", "", "synthetic-token", "fixture", 10, "fixture.invalid");

    @BeforeEach void setup() {
        String url = System.getenv("IW_ITERATION_DELETE_TEST_JDBC_URL");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:") || !url.contains("/devhub_worklog_test?")) throw new IllegalArgumentException("isolated DB required");
        var ds = new DriverManagerDataSource(url, "root", "");
        jdbc = new NamedParameterJdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String table : List.of("iteration_issue_worklog", "iteration_issue", "iteration_member_role", "iteration_member", "iteration_release_plan", "iteration", "worklog_deleted_entry", "worklog_deleted_issue_key", "worklog_deletion_revision")) {
            jdbc.getJdbcTemplate().execute("DELETE FROM external_zhaogang_" + table);
        }
        for (long id : List.of(1L, 2L)) {
            jdbc.update("INSERT INTO external_zhaogang_iteration(id,request_id,team_key,name,stage,creator_user_id,creator_user_name,updater_user_id,updater_user_name) VALUES(:id,:request,'fixture','验收迭代','DEVELOPING',101,'测试成员',101,'测试成员')", Map.of("id", id, "request", "fixture-"+id));
            jdbc.update("INSERT INTO external_zhaogang_iteration_member(iteration_id,workbench_team_id,workbench_team_name,coding_user_id,user_name) VALUES(:id,1,'测试团队',101,'测试成员')", Map.of("id",id));
            jdbc.update("INSERT INTO external_zhaogang_iteration_issue(id,iteration_id,source,project_name,issue_id,issue_code,issue_type,issue_type_name,title,sync_status,creator_user_id,creator_user_name) VALUES(:id,:iteration,'CODING','fixture-project',100,22,'SUB_TASK','子工作项','验收事项','SYNCED',101,'测试成员')", Map.of("id",id*10,"iteration",id));
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,
                new com.baomidou.mybatisplus.core.config.GlobalConfig().setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig()).setMetaObjectHandler(
                        new com.itwray.iw.web.core.mybatis.DefaultMetaObjectHandler()));
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), ds));
        for (Class<?> mapper : List.of(ZhaogangIterationMapper.class,ZhaogangIterationMemberMapper.class,ZhaogangIterationMemberRoleMapper.class,
                ZhaogangIterationIssueMapper.class,ZhaogangIterationIssueWorklogMapper.class,ZhaogangIterationReleasePlanMapper.class)) config.addMapper(mapper);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        repo = new MybatisTeamIterationRepository(session.getMapper(ZhaogangIterationMapper.class),session.getMapper(ZhaogangIterationMemberMapper.class),
                session.getMapper(ZhaogangIterationMemberRoleMapper.class),session.getMapper(ZhaogangIterationIssueMapper.class),
                session.getMapper(ZhaogangIterationIssueWorklogMapper.class),session.getMapper(ZhaogangIterationReleasePlanMapper.class));
    }

    private CodingOpenApiPort.IssueWorklog remote(long id, long localId) {
        long at = LocalDateTime.of(2026,11,25,10,30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        return new CodingOpenApiPort.IssueWorklog(id,new BigDecimal("2.5"),BigDecimal.ZERO,at,at,at,101,"","Dev Hub 工时登记 #"+localId);
    }

    @Test void registrationAndSnapshotAreDeduplicatedAndDeletionCannotResurrectAcrossIterations() {
        var local = repo.addWorklog(1,10,new BigDecimal("2.5"),LocalDateTime.of(2026,11,25,10,30),TeamIterationModels.IssueSyncStatus.PENDING,actor);
        repo.markWorklogSynced(local.getId(),"fixture-request");
        var remote = remote(9001,local.getId());
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(1,10,List.of(remote,remote),actor));
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(1,10,List.of(remote),actor));
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(2,20,List.of(remote),actor));
        var first = repo.findById(1).orElseThrow();
        assertThat(first.worklogs()).hasSize(1);
        assertThat(first.worklogs().get(0).getId()).isEqualTo(local.getId());
        assertThat(first.worklogs().get(0).getSource()).isEqualTo("WORKBENCH");
        assertThat(first.issues().get(0).getCodingRecordedHours()).isEqualByComparingTo("2.5");
        assertThat(repo.claimWorklogDeletion(local.getId())).isTrue();
        assertThat(repo.claimWorklogDeletion(local.getId())).isFalse();
        assertThat(repo.claimWorklogSync(local.getId())).isFalse();
        repo.releaseWorklogDeletion(local.getId());
        var journal = new WorklogMutationJournal(jdbc);
        tx.executeWithoutResult(t -> journal.worklogDeleted(10,"fixture",9001));
        tx.executeWithoutResult(t -> journal.worklogDeleted(10,"fixture",9001));
        // Simulate stale in-flight CODING sync publishing the deleted row after the journal.
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(2,20,List.of(remote),actor));
        assertThat(repo.findById(1).orElseThrow().worklogs()).isEmpty();
        var second = repo.findById(2).orElseThrow();
        assertThat(second.worklogs()).isEmpty();
        assertThat(second.issues().get(0).getCodingRecordedHours()).isEqualByComparingTo("0");
        assertThat(second.issues().get(0).getCodingWorklogCount()).isZero();
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT revision FROM external_zhaogang_worklog_deletion_revision WHERE coding_team_id=10",Long.class)).isEqualTo(1L);
    }

    @Test void syncDisappearanceKeepsAuditButHidesMissingRowsAndZeroesTotals() {
        var remote = remote(9002,999);
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(1,10,List.of(remote),actor));
        assertThat(repo.findById(1).orElseThrow().worklogs()).hasSize(1);
        tx.executeWithoutResult(t -> repo.replaceCodingWorklogs(1,10,List.of(),actor));
        var result = repo.findById(1).orElseThrow();
        assertThat(result.worklogs()).isEmpty();
        assertThat(result.issues().get(0).getCodingWorklogCount()).isZero();
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM external_zhaogang_iteration_issue_worklog WHERE coding_worklog_id=9002",Integer.class)).isEqualTo(1);
    }
}
