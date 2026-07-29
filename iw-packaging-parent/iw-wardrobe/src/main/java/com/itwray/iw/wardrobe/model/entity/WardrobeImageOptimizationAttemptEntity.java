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

    /** 发起本次尝试的登录用户，不随衣物所属人转移而变化。 */
    private Integer operatorUserId;

    private String status;

    private String claimToken;

    private LocalDateTime claimExpireTime;

    private LocalDateTime startTime;

    private LocalDateTime deadlineTime;

    private String provider;

    private String model;

    private String resultImageUrl;

    private String resultMimeType;

    private String revisedPrompt;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime completeTime;
}
