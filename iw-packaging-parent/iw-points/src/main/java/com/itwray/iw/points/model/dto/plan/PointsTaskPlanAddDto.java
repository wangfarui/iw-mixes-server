package com.itwray.iw.points.model.dto.plan;

import com.itwray.iw.web.model.dto.AddDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;


/**
 * 任务计划表 新增DTO
 *
 * @author wray
 * @since 2025-05-07
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "任务计划表 新增DTO")
public class PointsTaskPlanAddDto extends PointsTaskPlanScheduleDto implements AddDto {

    @Schema(title = "任务名称")
    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @Schema(title = "任务备注")
    private String taskRemark;

    @Schema(title = "奖励积分")
    private Integer rewardPoints;

    @Schema(title = "处罚积分")
    private Integer punishPoints;
    private Boolean bookkeepingEnabled;
    private com.itwray.iw.points.bookkeeping.TaskBookkeepingTemplateDto bookkeepingTemplate;
}
