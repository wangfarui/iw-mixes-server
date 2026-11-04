package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenCreated;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentModels.TokenStatus;
import com.itwray.iw.external.zhaogang.agent.ZhaogangAgentTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设置页 Agent 授权管理，仅接受浏览器 Cookie 会话。 */
@RestController
@RequestMapping("/external-service/api/zhaogang/agent-token")
@Validated
@Tag(name = "找钢工作台 Agent 授权")
public class ZhaogangAgentTokenController {

    private final ZhaogangSessionManager sessionManager;

    private final ZhaogangAgentTokenService tokenService;

    public ZhaogangAgentTokenController(ZhaogangSessionManager sessionManager,
                                        ZhaogangAgentTokenService tokenService) {
        this.sessionManager = sessionManager;
        this.tokenService = tokenService;
    }

    @GetMapping
    @Operation(summary = "查询当前用户的 Agent 授权状态")
    public GeneralResponse<TokenStatus> status(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(tokenService.status(sessionManager.resolve(request, response)));
    }

    @PostMapping
    @Operation(summary = "生成或重新生成当前用户的 Agent 授权令牌")
    public GeneralResponse<TokenCreated> create(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return GeneralResponse.success(tokenService.create(sessionManager.resolve(request, response)));
    }

    @DeleteMapping
    @Operation(summary = "删除当前用户的 Agent 授权令牌")
    public GeneralResponse<TokenStatus> delete(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        ZhaogangSession session = sessionManager.resolve(request, response);
        tokenService.delete(session);
        return GeneralResponse.success(tokenService.status(session));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
