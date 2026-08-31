package com.itwray.iw.external.zhaogang.calendar.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

public final class WorkCalendarEntities {

    private WorkCalendarEntities() {
    }

    @Data
    @TableName("external_zhaogang_work_calendar")
    public static class CalendarEntity {
        @TableId
        private Long id;
        private Long codingTeamId;
        private Integer versionNo;
    }

    @Data
    @TableName("external_zhaogang_work_calendar_day")
    public static class CalendarDayEntity {
        @TableId
        private Long id;
        private Long calendarId;
        private LocalDate workDate;
        private String dayType;
        private Long updaterUserId;
        private String updaterUserName;
    }

    @Data
    @TableName("external_zhaogang_work_calendar_leave")
    public static class CalendarLeaveEntity {
        @TableId
        private Long id;
        private Long codingTeamId;
        private Long codingUserId;
        private LocalDate leaveDate;
    }
}
