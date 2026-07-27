package com.itwray.iw.external.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 工具按日使用统计表。
 *
 * @author wray
 * @since 2026/7/27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("external_tool_usage_daily")
public class ExternalToolUsageDailyEntity extends BaseEntity<Long> {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    private String toolKey;

    private Long usageCount;
}
