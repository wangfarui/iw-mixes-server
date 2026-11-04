package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ZhaogangCodeIdentityMapper extends BaseMapper<IdentityEntity> {

    @Insert("""
            insert ignore into external_zhaogang_code_identity
              (coding_team_id, scope_type, scope_id, identity_type, identity_key, coding_user_id,
               ignored, author_name, source, updated_by)
            values (#{codingTeamId}, 'SELF', #{userId}, #{type}, #{key}, #{userId}, 0,
                    #{display}, 'DEFAULT', #{userId})
            """)
    int insertDefault(@Param("codingTeamId") long codingTeamId, @Param("userId") long userId,
                      @Param("type") String type, @Param("key") String key, @Param("display") String display);


    @Select("""
            select * from external_zhaogang_code_identity
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
             order by id
            """)
    List<IdentityEntity> selectByScope(@Param("codingTeamId") long codingTeamId,
                                       @Param("scopeType") String scopeType, @Param("scopeId") long scopeId);

    @Select("""
            select * from external_zhaogang_code_identity
             where coding_team_id = #{codingTeamId} and scope_type = #{scopeType} and scope_id = #{scopeId}
               and identity_type = #{identityType} and identity_key = #{identityKey} limit 1
            """)
    IdentityEntity selectOneByIdentity(@Param("codingTeamId") long codingTeamId,
                                       @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
                                       @Param("identityType") String identityType,
                                       @Param("identityKey") String identityKey);
}
