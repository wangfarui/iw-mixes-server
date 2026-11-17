package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.mapper.ZhaogangWorkbenchProfileMapper;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Explicit disposable schema only, exercising the real record constructor mapping. */
@EnabledIfEnvironmentVariable(named="IW_PROFILE_TEST_JDBC_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/profile_accept_[a-zA-Z0-9_]+.*")
class ZhaogangWorkbenchProfileMapperMysqlTest {
    @Test void phoneAndRoleWritesPreserveEachOtherAndTeamViewsUseTheSameProfile() {
        var ds = new DriverManagerDataSource(System.getenv("IW_PROFILE_TEST_JDBC_URL"), "root", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_profile (coding_team_id BIGINT,coding_user_id BIGINT,coding_role VARCHAR(16),role_configured TINYINT DEFAULT 0,last_used_at DATETIME,mobile VARCHAR(11),PRIMARY KEY(coding_team_id,coding_user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_team (id BIGINT PRIMARY KEY,coding_team_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS external_zhaogang_workbench_team_member (team_id BIGINT,coding_user_id BIGINT,PRIMARY KEY(team_id,coding_user_id))");
        long team = System.nanoTime();
        jdbc.update("INSERT INTO external_zhaogang_workbench_team VALUES (?,10),(?,10),(?,11)",team,team+1,team+2);
        jdbc.update("INSERT INTO external_zhaogang_workbench_team_member VALUES (?,?),(?,?),(?,?)",team,team,team+1,team,team+2,team);
        var configuration = new Configuration(new Environment("mobile-test",new JdbcTransactionFactory(),ds));
        configuration.addMapper(ZhaogangWorkbenchProfileMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = session.getMapper(ZhaogangWorkbenchProfileMapper.class);
            mapper.saveRole(10,team,CodingRole.QA,false);
            mapper.saveMobile(10,team,"13800000001");
            mapper.recordUse(10,team);
            var admin = mapper.findMemberProfiles(team,true).get(0);
            assertThat(admin.mobile()).isEqualTo("13800000001");
            assertThat(admin.codingRole()).isEqualTo(CodingRole.QA);
            assertThat(admin.lastUsedAt()).isNotNull();
            assertThat(mapper.findMemberProfiles(team+1,false).get(0).mobile()).isEqualTo("13800000001");
            assertThat(mapper.findMemberProfiles(team+1,false).get(0).lastUsedAt()).isNull();
            assertThat(mapper.findMemberProfiles(team+2,false).get(0).mobile()).isNull();
            mapper.saveMobile(10,team,null);
            mapper.saveRole(10,team,CodingRole.BACKEND,false);
            assertThat(mapper.findMobile(10,team)).isNull();
            assertThat(mapper.findRole(10,team).codingRole()).isEqualTo(CodingRole.BACKEND);
            assertThat(mapper.findMemberProfiles(team,true).get(0).lastUsedAt()).isEqualTo(admin.lastUsedAt());
        }
    }
}
