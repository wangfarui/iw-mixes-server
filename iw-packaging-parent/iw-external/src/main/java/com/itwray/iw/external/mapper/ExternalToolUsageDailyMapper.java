package com.itwray.iw.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itwray.iw.external.model.entity.ExternalToolUsageDailyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 工具按日使用统计 Mapper。
 *
 * @author wray
 * @since 2026/7/27
 */
@Mapper
public interface ExternalToolUsageDailyMapper extends BaseMapper<ExternalToolUsageDailyEntity> {

    @Insert("""
            INSERT INTO external_tool_usage_daily (stat_date, tool_key, usage_count)
            VALUES (#{statDate}, #{toolKey}, 1)
            ON DUPLICATE KEY UPDATE usage_count = usage_count + 1
            """)
    int incrementUsage(@Param("statDate") LocalDate statDate, @Param("toolKey") String toolKey);

    @Select("""
            SELECT tool_key AS toolKey,
                   SUM(usage_count) AS totalUsageCount,
                   SUM(CASE WHEN stat_date >= #{periodStartDate} THEN usage_count ELSE 0 END) AS periodUsageCount,
                   SUM(CASE WHEN stat_date = #{today} THEN usage_count ELSE 0 END) AS todayUsageCount
            FROM external_tool_usage_daily
            GROUP BY tool_key
            """)
    List<ToolUsageStatisticsRow> selectStatistics(@Param("periodStartDate") LocalDate periodStartDate,
                                                   @Param("today") LocalDate today);

    record ToolUsageStatisticsRow(String toolKey,
                                  Long totalUsageCount,
                                  Long periodUsageCount,
                                  Long todayUsageCount) {
    }
}
