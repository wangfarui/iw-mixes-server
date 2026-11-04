package com.itwray.iw.external.mapper;

import com.itwray.iw.external.zhaogang.agent.entity.ZhaogangAgentTokenEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ZhaogangAgentTokenMapper {

    @Select("""
            select coding_team_id, coding_user_id, user_name, avatar, token_hash, token_hint,
                   created_at, last_used_at
              from external_zhaogang_agent_token
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    ZhaogangAgentTokenEntity find(@Param("codingTeamId") long codingTeamId,
                                  @Param("codingUserId") long codingUserId);

    @Select("""
            select coding_team_id, coding_user_id, user_name, avatar, token_hash, token_hint,
                   created_at, last_used_at
              from external_zhaogang_agent_token
             where token_hash = #{tokenHash}
            """)
    ZhaogangAgentTokenEntity findByHash(@Param("tokenHash") String tokenHash);

    @Insert("""
            insert into external_zhaogang_agent_token
                (coding_team_id, coding_user_id, user_name, avatar, token_hash, token_hint,
                 created_at, last_used_at)
            values (#{codingTeamId}, #{codingUserId}, #{userName}, #{avatar}, #{tokenHash}, #{tokenHint},
                    current_timestamp, null)
            on duplicate key update
                user_name = values(user_name),
                avatar = values(avatar),
                token_hash = values(token_hash),
                token_hint = values(token_hint),
                created_at = current_timestamp,
                last_used_at = null
            """)
    int upsert(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
               @Param("userName") String userName, @Param("avatar") String avatar,
               @Param("tokenHash") String tokenHash, @Param("tokenHint") String tokenHint);

    @Update("""
            update external_zhaogang_agent_token
               set user_name = #{userName}, avatar = #{avatar}
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    int updateProfile(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
                      @Param("userName") String userName, @Param("avatar") String avatar);

    @Update("""
            update external_zhaogang_agent_token
               set last_used_at = #{lastUsedAt}
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    int touch(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
              @Param("lastUsedAt") LocalDateTime lastUsedAt);

    @Delete("""
            delete from external_zhaogang_agent_token
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    int delete(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId);
}
