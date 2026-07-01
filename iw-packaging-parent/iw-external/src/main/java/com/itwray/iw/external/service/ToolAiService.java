package com.itwray.iw.external.service;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.ToolAiGenerateDto;
import com.itwray.iw.external.model.vo.ToolAiGenerateVo;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 公开工具AI服务
 *
 * @author wray
 * @since 2026/7/1
 */
public interface ToolAiService {

    /**
     * 生成工具AI内容
     *
     * @param dto     请求参数
     * @param request HTTP请求
     * @return 生成结果
     */
    GeneralResponse<ToolAiGenerateVo> generate(ToolAiGenerateDto dto, HttpServletRequest request);
}
