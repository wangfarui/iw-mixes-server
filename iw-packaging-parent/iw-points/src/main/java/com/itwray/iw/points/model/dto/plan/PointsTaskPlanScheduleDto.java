package com.itwray.iw.points.model.dto.plan;

import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import lombok.Data;
import java.time.LocalDate;

/** Date rule shared by saving, conversion and the read-only preview. */
@Data
public class PointsTaskPlanScheduleDto {
    private TaskPlanCycleEnum planCycle;
    /** Omitted on creation means SOLAR; omitted on update preserves the calendar. */
    private String calendarType;
    /** Requested first occurrence for SOLAR; retained for older clients. */
    private LocalDate planDate;
    private Integer lunarYear;
    private Integer lunarMonth;
    private Integer lunarDay;
    private Boolean leapMonth;
    private Integer cycleDays;
    private Integer remindDays;
    private Integer deadlineDays;
}
