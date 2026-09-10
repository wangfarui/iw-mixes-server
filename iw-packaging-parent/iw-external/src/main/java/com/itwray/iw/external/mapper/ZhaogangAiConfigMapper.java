package com.itwray.iw.external.mapper;

import com.itwray.iw.external.zhaogang.ai.entity.ZhaogangAiConfigEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ZhaogangAiConfigMapper {

    @Select("""
            select * from external_zhaogang_ai_config
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    ZhaogangAiConfigEntity find(@Param("codingTeamId") long codingTeamId,
                                @Param("codingUserId") long codingUserId);

    @Insert("""
            insert into external_zhaogang_ai_config
                (coding_team_id, coding_user_id, api_url, api_key, model, execution_location)
            values (#{codingTeamId}, #{codingUserId}, #{apiUrl}, #{apiKey}, #{model}, #{executionLocation})
            on duplicate key update
                api_url = values(api_url), api_key = values(api_key), model = values(model),
                execution_location = values(execution_location), update_time = current_timestamp
            """)
    int upsert(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId,
               @Param("apiUrl") String apiUrl, @Param("apiKey") String apiKey,
               @Param("model") String model, @Param("executionLocation") String executionLocation);

    @Delete("""
            delete from external_zhaogang_ai_config
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
            """)
    int delete(@Param("codingTeamId") long codingTeamId, @Param("codingUserId") long codingUserId);
}
