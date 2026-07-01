package com.itwray.iw.external.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 外部工具AI调用记录表
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("external_tool_ai_records")
public class ExternalToolAiRecordEntity extends BaseEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String requestId;

    private String businessType;

    private String requestBody;

    private String systemPrompt;

    private String userPrompt;

    private String responseContent;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String status;

    private String failReason;

    private String clientIp;

    private String clientIpHash;

    private String userAgent;

    private Integer quotaTotalAfter;

    private Integer quotaTypeAfter;

    private Integer quotaIpAfter;
}
