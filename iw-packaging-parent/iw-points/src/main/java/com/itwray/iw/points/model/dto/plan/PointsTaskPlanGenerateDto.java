package com.itwray.iw.points.model.dto.plan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
@Schema(name = "立即生成计划待办")
public class PointsTaskPlanGenerateDto {
    @NotNull(message = "任务计划不能为空")
    @Positive(message = "任务计划无效")
    private Integer id;

    @NotNull(message = "请求标识不能为空")
    @Pattern(regexp = "[a-zA-Z0-9_-]{16,64}", message = "请求标识格式无效")
    @Schema(title = "同一次操作及重试使用相同标识，再次主动生成使用新标识")
    private String requestId;
}
