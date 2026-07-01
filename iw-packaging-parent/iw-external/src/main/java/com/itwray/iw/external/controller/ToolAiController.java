package com.itwray.iw.external.controller;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.ToolAiGenerateDto;
import com.itwray.iw.external.model.vo.ToolAiGenerateVo;
import com.itwray.iw.external.service.ToolAiService;
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
 * 公开工具AI接口
 *
 * @author wray
 * @since 2026/7/1
 */
@SkipWrapper
@RestController
@RequestMapping("/external-service/api/tools/ai")
@Validated
@Tag(name = "公开工具AI接口")
public class ToolAiController {

    private final ToolAiService toolAiService;

    public ToolAiController(ToolAiService toolAiService) {
        this.toolAiService = toolAiService;
    }

    @PostMapping("/generate")
    @Operation(summary = "公开工具AI生成")
    public GeneralResponse<ToolAiGenerateVo> generate(@RequestBody @Valid ToolAiGenerateDto dto,
                                                      HttpServletRequest request) {
        return toolAiService.generate(dto, request);
    }
}
