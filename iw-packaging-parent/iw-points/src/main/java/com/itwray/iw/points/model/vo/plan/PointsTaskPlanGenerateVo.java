package com.itwray.iw.points.model.vo.plan;

import lombok.Data;

import java.time.LocalDate;

@Data
public class PointsTaskPlanGenerateVo {
    private Integer taskId;
    private LocalDate planDate;
    private LocalDate deadlineDate;
    private boolean taskDeleted;
}
