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
@TableName("wardrobe_image_optimization_task")
public class WardrobeImageOptimizationTaskEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String taskId;

    private Integer itemId;

    private String fingerprint;

    private String sourceImageUrl;

    private String userPrompt;

    private String normalizedPrompt;

    private String ruleVersion;

    private String inputSnapshot;

    private String status;

    private Integer currentAttemptNo;

    private String resultImageUrl;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime resultDeletedTime;

    private LocalDateTime completeTime;
}
