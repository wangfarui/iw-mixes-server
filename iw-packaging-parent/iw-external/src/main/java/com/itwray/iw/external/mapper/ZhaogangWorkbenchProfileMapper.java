package com.itwray.iw.external.mapper;

import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.MemberProfile;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.RolePreference;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ZhaogangWorkbenchProfileMapper {

    @Select("""
            select coding_role as codingRole, role_configured as roleConfigured
              from external_zhaogang_workbench_profile
             where coding_team_id = #{codingTeamId} and coding_user_id = #{userId}
            """)
    RolePreference findRole(@Param("codingTeamId") long codingTeamId, @Param("userId") long userId);

    @Select("""
            select m.coding_user_id as userId, p.coding_role as codingRole,
                   case when #{includeActivity} then p.last_used_at else null end as lastUsedAt
              from external_zhaogang_workbench_team_member m
              join external_zhaogang_workbench_team t on t.id = m.team_id
              left join external_zhaogang_workbench_profile p
                on p.coding_team_id = t.coding_team_id and p.coding_user_id = m.coding_user_id
             where t.id = #{teamId}
            """)
    List<MemberProfile> findMemberProfiles(@Param("teamId") long teamId,
                                           @Param("includeActivity") boolean includeActivity);

    @Insert("""
            insert into external_zhaogang_workbench_profile
                (coding_team_id, coding_user_id, coding_role, role_configured)
            values (#{codingTeamId}, #{userId}, #{codingRole,jdbcType=VARCHAR}, 1)
            on duplicate key update
                coding_role = if(#{initializeOnly} and role_configured, coding_role,
                                 #{codingRole,jdbcType=VARCHAR}),
                role_configured = 1
            """)
    void saveRole(@Param("codingTeamId") long codingTeamId, @Param("userId") long userId,
                  @Param("codingRole") CodingRole codingRole, @Param("initializeOnly") boolean initializeOnly);

    @Insert("""
            insert into external_zhaogang_workbench_profile (coding_team_id, coding_user_id, last_used_at)
            values (#{codingTeamId}, #{userId}, timestampadd(hour, 8, utc_timestamp()))
            on duplicate key update
                last_used_at = if(last_used_at is null or last_used_at <=
                    timestampadd(minute, -5, timestampadd(hour, 8, utc_timestamp())),
                    timestampadd(hour, 8, utc_timestamp()), last_used_at)
            """)
    void recordUse(@Param("codingTeamId") long codingTeamId, @Param("userId") long userId);
}
