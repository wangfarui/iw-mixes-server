package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.calendar.entity.WorkCalendarEntities.CalendarDayEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface ZhaogangWorkCalendarDayMapper extends BaseMapper<CalendarDayEntity> {

    @Select("""
            select * from external_zhaogang_work_calendar_day
             where calendar_id = #{calendarId} and work_date >= #{from} and work_date < #{toExclusive}
             order by work_date
            """)
    List<CalendarDayEntity> selectRange(@Param("calendarId") long calendarId,
                                        @Param("from") LocalDate from,
                                        @Param("toExclusive") LocalDate toExclusive);

    @Insert("""
            insert into external_zhaogang_work_calendar_day
                (calendar_id, work_date, day_type, updater_user_id, updater_user_name)
            values (#{calendarId}, #{workDate}, #{dayType}, #{updaterUserId}, #{updaterUserName})
            on duplicate key update day_type = values(day_type), updater_user_id = values(updater_user_id),
                                    updater_user_name = values(updater_user_name), update_time = current_timestamp
            """)
    int upsert(@Param("calendarId") long calendarId,
               @Param("workDate") LocalDate workDate,
               @Param("dayType") String dayType,
               @Param("updaterUserId") long updaterUserId,
               @Param("updaterUserName") String updaterUserName);

    @Delete("""
            delete from external_zhaogang_work_calendar_day
             where calendar_id = #{calendarId} and work_date = #{workDate}
            """)
    int deleteDay(@Param("calendarId") long calendarId, @Param("workDate") LocalDate workDate);
}
