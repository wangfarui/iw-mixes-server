package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.k8s.entity.K8sTokenEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ZhaogangK8sTokenMapper extends BaseMapper<K8sTokenEntity> {

    @Select("""
            select * from external_zhaogang_k8s_token
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
             order by environment
            """)
    List<K8sTokenEntity> findAll(@Param("codingTeamId") long codingTeamId,
                                 @Param("codingUserId") long codingUserId);

    @Select("""
            select * from external_zhaogang_k8s_token
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
               and environment = #{environment}
            """)
    K8sTokenEntity find(@Param("codingTeamId") long codingTeamId,
                        @Param("codingUserId") long codingUserId,
                        @Param("environment") String environment);

    @Insert("""
            insert into external_zhaogang_k8s_token
                (coding_team_id, coding_user_id, environment, token_plaintext)
            values (#{codingTeamId}, #{codingUserId}, #{environment}, #{tokenPlaintext})
            on duplicate key update token_plaintext = values(token_plaintext), update_time = current_timestamp
            """)
    int upsert(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
               @Param("environment") String environment, @Param("tokenPlaintext") String tokenPlaintext);

    @Delete("""
            delete from external_zhaogang_k8s_token
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
               and environment = #{environment}
            """)
    int delete(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
               @Param("environment") String environment);
}
