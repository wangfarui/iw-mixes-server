package com.itwray.iw.external.service.impl;

import com.itwray.iw.external.mapper.ExternalToolUsageDailyMapper;
import com.itwray.iw.external.model.enums.ToolUsageToolKeyEnum;
import com.itwray.iw.external.model.vo.ToolUsageSummaryVo;
import com.itwray.iw.external.service.ToolUsageService;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * 工具使用统计服务实现。
 *
 * @author wray
 * @since 2026/7/27
 */
@Service
public class ToolUsageServiceImpl implements ToolUsageService {

    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RANKING_PERIOD_DAYS = 30;
    private static final int POPULAR_TOOL_LIMIT = 10;

    private final ExternalToolUsageDailyMapper mapper;
    private final Clock clock;

    @Autowired
    public ToolUsageServiceImpl(ExternalToolUsageDailyMapper mapper) {
        this(mapper, Clock.system(STAT_ZONE));
    }

    ToolUsageServiceImpl(ExternalToolUsageDailyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void record(String toolKey) {
        ToolUsageToolKeyEnum supportedTool = ToolUsageToolKeyEnum.findByToolKey(toolKey);
        if (supportedTool == null) {
            throw new BusinessException("不支持的工具标识");
        }
        mapper.incrementUsage(LocalDate.now(clock), supportedTool.getToolKey());
    }

    @Override
    public ToolUsageSummaryVo summary() {
        LocalDate today = LocalDate.now(clock);
        LocalDate periodStartDate = today.minusDays(RANKING_PERIOD_DAYS - 1L);
        List<ToolUsageSummaryVo.ToolStat> toolStats = mapper.selectStatistics(periodStartDate, today).stream()
                .map(this::toToolStat)
                .sorted(Comparator.comparing(ToolUsageSummaryVo.ToolStat::getToolKey))
                .toList();
        List<ToolUsageSummaryVo.ToolStat> popularTools = toolStats.stream()
                .sorted(Comparator.comparing(ToolUsageSummaryVo.ToolStat::getPeriodUsageCount).reversed()
                        .thenComparing(ToolUsageSummaryVo.ToolStat::getTotalUsageCount, Comparator.reverseOrder())
                        .thenComparing(ToolUsageSummaryVo.ToolStat::getToolKey))
                .limit(POPULAR_TOOL_LIMIT)
                .toList();
        long totalUsageCount = toolStats.stream().mapToLong(ToolUsageSummaryVo.ToolStat::getTotalUsageCount).sum();
        long todayUsageCount = toolStats.stream().mapToLong(ToolUsageSummaryVo.ToolStat::getTodayUsageCount).sum();
        return ToolUsageSummaryVo.builder()
                .totalUsageCount(totalUsageCount)
                .todayUsageCount(todayUsageCount)
                .rankingPeriodDays(RANKING_PERIOD_DAYS)
                .toolStats(toolStats)
                .popularTools(popularTools)
                .build();
    }

    private ToolUsageSummaryVo.ToolStat toToolStat(ExternalToolUsageDailyMapper.ToolUsageStatisticsRow row) {
        return ToolUsageSummaryVo.ToolStat.builder()
                .toolKey(row.toolKey())
                .totalUsageCount(defaultCount(row.totalUsageCount()))
                .periodUsageCount(defaultCount(row.periodUsageCount()))
                .todayUsageCount(defaultCount(row.todayUsageCount()))
                .build();
    }

    private long defaultCount(Long value) {
        return value == null ? 0L : value;
    }
}
