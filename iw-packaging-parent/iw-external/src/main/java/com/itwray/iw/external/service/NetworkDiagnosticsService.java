package com.itwray.iw.external.service;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.NetworkDiagnosticsCheckDto;
import com.itwray.iw.external.model.vo.NetworkDiagnosticsResultVo;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 网络诊断服务。
 *
 * @author wray
 * @since 2026/7/1
 */
public interface NetworkDiagnosticsService {

    /**
     * 执行网络诊断。
     *
     * @param dto     查询参数
     * @param request HTTP请求
     * @return 诊断结果
     */
    GeneralResponse<NetworkDiagnosticsResultVo> check(NetworkDiagnosticsCheckDto dto, HttpServletRequest request);
}
