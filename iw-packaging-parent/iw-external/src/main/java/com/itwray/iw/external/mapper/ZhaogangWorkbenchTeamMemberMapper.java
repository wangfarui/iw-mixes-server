package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.MemberEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ZhaogangWorkbenchTeamMemberMapper extends BaseMapper<MemberEntity> {

    @Insert("""
            insert ignore into external_zhaogang_workbench_team_member
                (team_id, coding_user_id, user_name, avatar, sort_no)
            select #{teamId}, #{codingUserId}, #{userName}, #{avatar}, coalesce(max(sort_no), 0) + 1
              from external_zhaogang_workbench_team_member
             where coding_user_id = #{codingUserId}
            """)
    int insertIgnore(@Param("teamId") long teamId, @Param("codingUserId") long codingUserId,
                     @Param("userName") String userName, @Param("avatar") String avatar);

    @Update("""
            update external_zhaogang_workbench_team_member
               set user_name = #{userName}, avatar = #{avatar}, update_time = current_timestamp
             where coding_user_id = #{codingUserId}
            """)
    int updateProfile(@Param("codingUserId") long codingUserId, @Param("userName") String userName,
                      @Param("avatar") String avatar);

    @Select("""
            select team_id from external_zhaogang_workbench_team_member
             where coding_user_id = #{codingUserId}
             order by sort_no asc, id asc
            """)
    List<Long> selectTeamIds(@Param("codingUserId") long codingUserId);

    @Update("""
            update external_zhaogang_workbench_team_member
               set sort_no = #{sortNo}
             where coding_user_id = #{codingUserId} and team_id = #{teamId}
            """)
    int updateSortNo(@Param("codingUserId") long codingUserId, @Param("teamId") long teamId,
                     @Param("sortNo") int sortNo);

    @Delete("""
            delete from external_zhaogang_workbench_team_member
             where team_id = #{teamId} and coding_user_id = #{codingUserId}
            """)
    int deleteMember(@Param("teamId") long teamId, @Param("codingUserId") long codingUserId);

    @Delete("delete from external_zhaogang_workbench_team_member where team_id = #{teamId}")
    int deleteByTeamId(@Param("teamId") long teamId);
}
