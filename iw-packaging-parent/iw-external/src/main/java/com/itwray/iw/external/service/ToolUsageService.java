package com.itwray.iw.external.service;

import com.itwray.iw.external.model.vo.ToolUsageSummaryVo;

/**
 * 工具使用统计服务。
 *
 * @author wray
 * @since 2026/7/27
 */
public interface ToolUsageService {

    void record(String toolKey);

    ToolUsageSummaryVo summary();
}
