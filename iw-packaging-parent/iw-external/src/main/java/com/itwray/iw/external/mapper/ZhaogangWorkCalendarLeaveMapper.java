package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.zhaogang.calendar.entity.WorkCalendarEntities.CalendarLeaveEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface ZhaogangWorkCalendarLeaveMapper extends BaseMapper<CalendarLeaveEntity> {

    @Select("""
            select leave_date from external_zhaogang_work_calendar_leave
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
               and leave_date >= #{from} and leave_date < #{toExclusive}
             order by leave_date
            """)
    List<LocalDate> selectRange(@Param("codingTeamId") long codingTeamId,
                                @Param("codingUserId") long codingUserId,
                                @Param("from") LocalDate from,
                                @Param("toExclusive") LocalDate toExclusive);

    @Insert("""
            insert ignore into external_zhaogang_work_calendar_leave
                (coding_team_id, coding_user_id, leave_date)
            values (#{codingTeamId}, #{codingUserId}, #{leaveDate})
            """)
    int insertLeave(@Param("codingTeamId") long codingTeamId,
                    @Param("codingUserId") long codingUserId,
                    @Param("leaveDate") LocalDate leaveDate);

    @Delete("""
            delete from external_zhaogang_work_calendar_leave
             where coding_team_id = #{codingTeamId} and coding_user_id = #{codingUserId}
               and leave_date = #{leaveDate}
            """)
    int deleteLeave(@Param("codingTeamId") long codingTeamId,
                    @Param("codingUserId") long codingUserId,
                    @Param("leaveDate") LocalDate leaveDate);
}
