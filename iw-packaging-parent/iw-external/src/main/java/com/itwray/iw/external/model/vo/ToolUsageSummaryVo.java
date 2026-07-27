package com.itwray.iw.external.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工具使用统计摘要。
 *
 * @author wray
 * @since 2026/7/27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "工具使用统计摘要")
public class ToolUsageSummaryVo {

    private Long totalUsageCount;

    private Long todayUsageCount;

    private Integer rankingPeriodDays;

    private List<ToolStat> toolStats;

    private List<ToolStat> popularTools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "工具使用统计项")
    public static class ToolStat {

        private String toolKey;

        private Long totalUsageCount;

        private Long periodUsageCount;

        private Long todayUsageCount;
    }
}
