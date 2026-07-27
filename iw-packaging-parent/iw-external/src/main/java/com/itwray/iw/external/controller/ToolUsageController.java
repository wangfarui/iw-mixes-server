package com.itwray.iw.external.controller;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.ToolUsageRecordDto;
import com.itwray.iw.external.model.vo.ToolUsageSummaryVo;
import com.itwray.iw.external.service.ToolUsageService;
import com.itwray.iw.external.service.impl.ToolUsageRateLimiter;
import com.itwray.iw.web.annotation.SkipWrapper;
import com.itwray.iw.web.utils.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具使用统计公开接口。
 *
 * @author wray
 * @since 2026/7/27
 */
@SkipWrapper
@RestController
@RequestMapping("/external-service/api/tools/usage")
@Tag(name = "工具使用统计公开接口")
public class ToolUsageController {

    private final ToolUsageService toolUsageService;
    private final ToolUsageRateLimiter rateLimiter;

    public ToolUsageController(ToolUsageService toolUsageService, ToolUsageRateLimiter rateLimiter) {
        this.toolUsageService = toolUsageService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/record")
    @Operation(summary = "记录工具使用")
    public GeneralResponse<Void> record(@RequestBody @Valid ToolUsageRecordDto dto, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(IpUtils.getClientIp(request))) {
            return new GeneralResponse<>(429, "请求过于频繁，请稍后再试");
        }
        toolUsageService.record(dto.getToolKey());
        return GeneralResponse.success();
    }

    @GetMapping("/summary")
    @Operation(summary = "查询工具使用统计摘要")
    public GeneralResponse<ToolUsageSummaryVo> summary() {
        return GeneralResponse.success(toolUsageService.summary());
    }
}
