package com.itwray.iw.external.controller;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.NetworkDiagnosticsCheckDto;
import com.itwray.iw.external.model.vo.NetworkDiagnosticsResultVo;
import com.itwray.iw.external.service.NetworkDiagnosticsService;
import com.itwray.iw.web.annotation.SkipWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网络诊断公开接口。
 *
 * @author wray
 * @since 2026/7/1
 */
@RestController
@RequestMapping("/external-service/api/network-diagnostics")
@Validated
@Tag(name = "网络诊断接口")
@SkipWrapper
public class NetworkDiagnosticsController {

    private final NetworkDiagnosticsService networkDiagnosticsService;

    public NetworkDiagnosticsController(NetworkDiagnosticsService networkDiagnosticsService) {
        this.networkDiagnosticsService = networkDiagnosticsService;
    }

    @PostMapping("/check")
    @Operation(summary = "执行网络诊断")
    public GeneralResponse<NetworkDiagnosticsResultVo> check(@RequestBody @Valid NetworkDiagnosticsCheckDto dto,
                                                            HttpServletRequest request) {
        return networkDiagnosticsService.check(dto, request);
    }
}
