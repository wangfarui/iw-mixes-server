package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.team.entity.WorkbenchTeamEntities.TeamEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ZhaogangWorkbenchTeamMapper extends BaseMapper<TeamEntity> {

    @Select("""
            select t.* from external_zhaogang_workbench_team t
              join external_zhaogang_workbench_team_member m on m.team_id = t.id
             where m.coding_user_id = #{userId}
             order by m.sort_no asc, m.id asc
            """)
    List<TeamEntity> selectByMember(@Param("userId") long userId);

    @Update("""
            update external_zhaogang_workbench_team
               set name = #{name}, version_no = version_no + 1, update_time = current_timestamp
             where id = #{teamId} and administrator_user_id = #{administratorUserId}
               and version_no = #{versionNo}
            """)
    int renameAsAdministrator(@Param("teamId") long teamId,
                              @Param("administratorUserId") long administratorUserId,
                              @Param("versionNo") int versionNo, @Param("name") String name);

    @Update("""
            update external_zhaogang_workbench_team
               set version_no = version_no + 1, update_time = current_timestamp
             where id = #{teamId} and administrator_user_id = #{administratorUserId}
               and version_no = #{versionNo}
            """)
    int touchAsAdministrator(@Param("teamId") long teamId,
                             @Param("administratorUserId") long administratorUserId,
                             @Param("versionNo") int versionNo);

    @Update("""
            update external_zhaogang_workbench_team
               set administrator_user_id = #{successorUserId}, version_no = version_no + 1,
                   update_time = current_timestamp
             where id = #{teamId} and administrator_user_id = #{administratorUserId}
               and version_no = #{versionNo}
               and exists (select 1 from external_zhaogang_workbench_team_member m
                            where m.team_id = #{teamId} and m.coding_user_id = #{successorUserId})
            """)
    int transferAdministrator(@Param("teamId") long teamId,
                              @Param("administratorUserId") long administratorUserId,
                              @Param("versionNo") int versionNo,
                              @Param("successorUserId") long successorUserId);

    @Update("""
            update external_zhaogang_workbench_team
               set version_no = version_no + 1, update_time = current_timestamp
             where id = #{teamId}
            """)
    int touch(@Param("teamId") long teamId);

    @Update("""
            update external_zhaogang_workbench_team
               set version_no = version_no + 1, update_time = current_timestamp
             where id = #{teamId} and version_no = #{versionNo}
            """)
    int touchWithVersion(@Param("teamId") long teamId, @Param("versionNo") int versionNo);

    @Delete("""
            delete from external_zhaogang_workbench_team
             where id = #{teamId} and administrator_user_id = #{administratorUserId}
               and version_no = #{versionNo}
            """)
    int dissolve(@Param("teamId") long teamId, @Param("administratorUserId") long administratorUserId,
                 @Param("versionNo") int versionNo);
}
