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
@TableName("wardrobe_image_file_cleanup")
public class WardrobeImageFileCleanupEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    private String taskId;
    private Integer itemId;
    private Integer attemptNo;
    private String fileUrl;
    private String reason;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private LocalDateTime lastAttemptTime;
    private String lastError;
    private String claimToken;
    private LocalDateTime claimExpireTime;
    private LocalDateTime manualRequiredTime;
    private LocalDateTime completeTime;
}
