package com.itwray.iw.points.model.dto.plan;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PointsTaskPlanPreviewDto extends PointsTaskPlanScheduleDto {
    /** Optional owned plan; preserves the current cursor when its rule is unchanged. */
    private Integer id;
}
