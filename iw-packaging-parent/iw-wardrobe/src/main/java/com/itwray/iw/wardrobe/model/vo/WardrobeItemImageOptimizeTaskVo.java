package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 衣物图片AI优化任务 VO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "衣物图片AI优化任务VO")
public class WardrobeItemImageOptimizeTaskVo {

    @Schema(title = "任务ID")
    private String taskId;

    @Schema(title = "衣物ID")
    private Integer itemId;

    @Schema(title = "用户ID")
    private Integer userId;

    @Schema(title = "任务状态")
    private String status;

    @Schema(title = "优化后的图片地址")
    private String itemImage;

    @Schema(title = "错误信息")
    private String errorMessage;

    @Schema(title = "当前尝试编号")
    private Integer attemptNo;

    @Schema(title = "是否允许重试")
    private Boolean retryable;

    @Schema(title = "当前尝试期限")
    private LocalDateTime deadlineAt;
}
