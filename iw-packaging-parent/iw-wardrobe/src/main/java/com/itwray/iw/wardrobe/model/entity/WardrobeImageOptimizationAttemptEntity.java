package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wardrobe_image_optimization_attempt")
public class WardrobeImageOptimizationAttemptEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String taskId;

    private Integer attemptNo;

    private String status;

    private String claimToken;

    private LocalDateTime claimExpireTime;

    private LocalDateTime nextPollTime;

    private LocalDateTime startTime;

    private LocalDateTime deadlineTime;

    private String provider;

    private String externalTaskId;

    private String resultImageUrl;

    private String resultMimeType;

    private String revisedPrompt;

    private String errorMessage;

    private LocalDateTime completeTime;
}
