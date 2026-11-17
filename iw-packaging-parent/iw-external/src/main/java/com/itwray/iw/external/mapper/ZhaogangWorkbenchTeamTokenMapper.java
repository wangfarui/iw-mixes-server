package com.itwray.iw.external.mapper;

import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.ManagementTokenStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface ZhaogangWorkbenchTeamTokenMapper {

    @Select("""
            select true as configured, owner_user_id as ownerUserId, owner_name as ownerName,
                   verified_at as verifiedAt
              from external_zhaogang_workbench_team_token where team_id = #{teamId}
            """)
    ManagementTokenStatus status(@Param("teamId") long teamId);

    @Select("""
            select owner_user_id as ownerUserId, token_ciphertext as ciphertext
              from external_zhaogang_workbench_team_token where team_id = #{teamId}
            """)
    StoredManagementToken credential(@Param("teamId") long teamId);

    record StoredManagementToken(long ownerUserId, String ciphertext) {
        @Override
        public String toString() {
            return "StoredManagementToken[ownerUserId=" + ownerUserId + "]";
        }
    }

    @Insert("""
            insert into external_zhaogang_workbench_team_token
                (team_id, token_ciphertext, owner_user_id, owner_name, verified_at)
            values (#{teamId}, #{ciphertext}, #{ownerUserId}, #{ownerName}, #{verifiedAt})
            on duplicate key update token_ciphertext = values(token_ciphertext),
                owner_user_id = values(owner_user_id), owner_name = values(owner_name), verified_at = values(verified_at)
            """)
    void save(@Param("teamId") long teamId, @Param("ciphertext") String ciphertext,
              @Param("ownerUserId") long ownerUserId, @Param("ownerName") String ownerName,
              @Param("verifiedAt") LocalDateTime verifiedAt);

    @Delete("delete from external_zhaogang_workbench_team_token where team_id = #{teamId}")
    void deleteByTeamId(@Param("teamId") long teamId);
}
