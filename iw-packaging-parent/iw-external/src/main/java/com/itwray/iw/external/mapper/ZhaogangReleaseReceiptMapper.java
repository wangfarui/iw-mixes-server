package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.release.entity.ReleaseReceiptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@org.apache.ibatis.annotations.Mapper
public interface ZhaogangReleaseReceiptMapper extends BaseMapper<ReleaseReceiptEntity> {

    @Select("""
            select coding_team_id, coding_user_id, release_id, read_at
              from external_zhaogang_release_receipt
             where coding_team_id = #{codingTeamId}
               and coding_user_id = #{codingUserId}
               and release_id = #{releaseId}
            """)
    ReleaseReceiptEntity find(@Param("codingTeamId") long codingTeamId,
                              @Param("codingUserId") long codingUserId,
                              @Param("releaseId") String releaseId);

    @Insert("""
            insert into external_zhaogang_release_receipt
                (coding_team_id, coding_user_id, release_id, read_at)
            values (#{codingTeamId}, #{codingUserId}, #{releaseId}, current_timestamp)
            on duplicate key update read_at = read_at
            """)
    int upsert(@Param("codingTeamId") long codingTeamId,
               @Param("codingUserId") long codingUserId,
               @Param("releaseId") String releaseId);
}
