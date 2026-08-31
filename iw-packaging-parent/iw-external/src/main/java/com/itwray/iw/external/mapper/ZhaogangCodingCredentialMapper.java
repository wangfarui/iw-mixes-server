package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.credential.entity.CodingCredentialEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ZhaogangCodingCredentialMapper extends BaseMapper<CodingCredentialEntity> {

    @Select("""
            select * from external_zhaogang_coding_credential
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    CodingCredentialEntity find(@Param("codingTeamId") long codingTeamId,
                                @Param("codingUserId") long codingUserId);

    @Insert("""
            insert into external_zhaogang_coding_credential
                (coding_team_id, coding_user_id, token_plaintext, token_fingerprint,
                 user_name, avatar, last_verified_at)
            values (#{codingTeamId}, #{codingUserId}, #{tokenPlaintext}, #{tokenFingerprint},
                    #{userName}, #{avatar}, current_timestamp)
            on duplicate key update
                token_plaintext = values(token_plaintext),
                token_fingerprint = values(token_fingerprint),
                user_name = values(user_name),
                avatar = values(avatar),
                last_verified_at = current_timestamp,
                update_time = current_timestamp
            """)
    int upsert(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
               @Param("tokenPlaintext") String tokenPlaintext, @Param("tokenFingerprint") String tokenFingerprint,
               @Param("userName") String userName, @Param("avatar") String avatar);

    @Delete("""
            delete from external_zhaogang_coding_credential
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    int delete(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId);
}
