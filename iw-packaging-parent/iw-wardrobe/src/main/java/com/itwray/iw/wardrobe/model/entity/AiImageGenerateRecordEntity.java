package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI图片生成记录表
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_image_generate_record")
public class AiImageGenerateRecordEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String dedupeKey;

    private String businessType;

    private String businessCustomCategory;

    private String businessCategory;

    private String businessId;

    private String sourceImageUrl;

    private String prompt;

    private String taskId;

    private String externalTaskId;

    private String status;

    private String resultImageUrl;

    private String resultMimeType;

    private String revisedPrompt;

    private String errorMessage;

    private Integer hitCount;

    private LocalDateTime lastHitTime;
}
