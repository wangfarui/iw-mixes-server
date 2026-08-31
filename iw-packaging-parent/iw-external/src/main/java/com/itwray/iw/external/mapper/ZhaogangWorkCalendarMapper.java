package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.calendar.entity.WorkCalendarEntities.CalendarEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ZhaogangWorkCalendarMapper extends BaseMapper<CalendarEntity> {

    @Insert("""
            insert ignore into external_zhaogang_work_calendar (coding_team_id, version_no)
            values (#{codingTeamId}, 0)
            """)
    int insertIgnore(@Param("codingTeamId") long codingTeamId);

    @Select("select * from external_zhaogang_work_calendar where coding_team_id = #{codingTeamId}")
    CalendarEntity selectByCodingTeamId(@Param("codingTeamId") long codingTeamId);

    @Update("""
            update external_zhaogang_work_calendar
               set version_no = version_no + 1, update_time = current_timestamp
             where id = #{calendarId}
            """)
    int touch(@Param("calendarId") long calendarId);
}
