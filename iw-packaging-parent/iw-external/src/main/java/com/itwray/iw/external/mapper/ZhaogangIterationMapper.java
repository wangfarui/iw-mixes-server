package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.iteration.entity.TeamIterationEntities.IterationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ZhaogangIterationMapper extends BaseMapper<IterationEntity> {

    @Select("""
            <script>
            select i.*
              from external_zhaogang_iteration i
             where i.deleted = 0
               and i.team_key = #{teamKey}
               and exists (
                   select 1 from external_zhaogang_iteration_member mine
                    where mine.iteration_id = i.id and mine.coding_user_id = #{currentUserId} and mine.deleted = 0
               )
               <if test='stage != null'>and i.stage = #{stage}</if>
               <if test='memberUserId != null'>
               and exists (
                   select 1 from external_zhaogang_iteration_member selected_member
                    where selected_member.iteration_id = i.id
                      and selected_member.coding_user_id = #{memberUserId}
                      and selected_member.deleted = 0
               )
               </if>
               <if test='keyword != null and keyword != ""'>
               and i.name like concat('%', #{keyword}, '%')
               </if>
             order by i.update_time desc, i.id desc
             limit #{offset}, #{pageSize}
            </script>
            """)
    List<IterationEntity> selectBoardPage(@Param("teamKey") String teamKey,
                                          @Param("currentUserId") long currentUserId,
                                          @Param("stage") String stage,
                                          @Param("memberUserId") Long memberUserId,
                                          @Param("keyword") String keyword,
                                          @Param("offset") long offset,
                                          @Param("pageSize") int pageSize);

    @Select("""
            <script>
            select count(1)
              from external_zhaogang_iteration i
             where i.deleted = 0
               and i.team_key = #{teamKey}
               and exists (
                   select 1 from external_zhaogang_iteration_member mine
                    where mine.iteration_id = i.id and mine.coding_user_id = #{currentUserId} and mine.deleted = 0
               )
               <if test='stage != null'>and i.stage = #{stage}</if>
               <if test='memberUserId != null'>
               and exists (
                   select 1 from external_zhaogang_iteration_member selected_member
                    where selected_member.iteration_id = i.id
                      and selected_member.coding_user_id = #{memberUserId}
                      and selected_member.deleted = 0
               )
               </if>
               <if test='keyword != null and keyword != ""'>
               and i.name like concat('%', #{keyword}, '%')
               </if>
            </script>
            """)
    long countBoard(@Param("teamKey") String teamKey,
                    @Param("currentUserId") long currentUserId,
                    @Param("stage") String stage,
                    @Param("memberUserId") Long memberUserId,
                    @Param("keyword") String keyword);

    @Update("""
            update external_zhaogang_iteration
               set name = #{name}, version = coalesce(#{version}, version), stage = #{stage},
                   start_date = #{startDate},
                   released_at = case
                       when #{stage} = 'RELEASED' then coalesce(released_at, current_timestamp)
                       else null
                   end,
                   planned_release_date = #{plannedReleaseDate}, updater_user_id = #{updaterUserId},
                   updater_user_name = #{updaterUserName}, version_no = version_no + 1,
                   update_time = current_timestamp
             where id = #{id} and version_no = #{versionNo} and deleted = 0
            """)
    int updateBasic(@Param("id") long id, @Param("versionNo") int versionNo,
                    @Param("name") String name, @Param("version") String version,
                    @Param("stage") String stage,
                    @Param("startDate") LocalDate startDate,
                    @Param("plannedReleaseDate") LocalDate plannedReleaseDate,
                    @Param("updaterUserId") long updaterUserId, @Param("updaterUserName") String updaterUserName);

    @Update("""
            update external_zhaogang_iteration
               set stage = #{stage}, released_at = #{releasedAt}, updater_user_id = #{updaterUserId},
                   updater_user_name = #{updaterUserName}, version_no = version_no + 1,
                   update_time = current_timestamp
             where id = #{id} and version_no = #{versionNo} and deleted = 0
            """)
    int updateStage(@Param("id") long id, @Param("versionNo") int versionNo, @Param("stage") String stage,
                    @Param("releasedAt") LocalDateTime releasedAt, @Param("updaterUserId") long updaterUserId,
                    @Param("updaterUserName") String updaterUserName);

    @Update("""
            update external_zhaogang_iteration
               set updater_user_id = #{updaterUserId}, updater_user_name = #{updaterUserName},
                   version_no = version_no + 1, update_time = current_timestamp
             where id = #{id} and version_no = #{versionNo} and deleted = 0
            """)
    int touchWithVersion(@Param("id") long id, @Param("versionNo") int versionNo,
                         @Param("updaterUserId") long updaterUserId,
                         @Param("updaterUserName") String updaterUserName);

    @Update("""
            update external_zhaogang_iteration
               set updater_user_id = #{updaterUserId}, updater_user_name = #{updaterUserName},
                   version_no = version_no + 1, update_time = current_timestamp
             where id = #{id} and deleted = 0
            """)
    int touch(@Param("id") long id, @Param("updaterUserId") long updaterUserId,
              @Param("updaterUserName") String updaterUserName);

    @Update("""
            update external_zhaogang_iteration
               set deleted = 1, updater_user_id = #{updaterUserId}, updater_user_name = #{updaterUserName},
                   version_no = version_no + 1, update_time = current_timestamp
             where id = #{id} and deleted = 0
            """)
    int softDelete(@Param("id") long id, @Param("updaterUserId") long updaterUserId,
                   @Param("updaterUserName") String updaterUserName);
}
