package com.itwray.iw.external.zhaogang.ai;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.ZhaogangSession;
import com.itwray.iw.external.zhaogang.ZhaogangSessionManager;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.AgentRedeem;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.AgentTicket;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConfigCommand;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConfigStatus;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiModels.ConnectionTestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/external-service/api/zhaogang/ai")
public class ZhaogangAiController {

    private final ZhaogangSessionManager sessions;
    private final ZhaogangAiConfigService aiConfig;
    private final ZhaogangAgentTicketService tickets;
    private final ZhaogangProperties properties;

    public ZhaogangAiController(ZhaogangSessionManager sessions, ZhaogangAiConfigService aiConfig,
                                ZhaogangAgentTicketService tickets, ZhaogangProperties properties) {
        this.sessions = sessions;
        this.aiConfig = aiConfig;
        this.tickets = tickets;
        this.properties = properties;
    }

    @GetMapping("/config")
    public GeneralResponse<ConfigStatus> config(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        return GeneralResponse.success(aiConfig.status(teamId(session), session.userId()));
    }

    @PutMapping("/config")
    public GeneralResponse<ConfigStatus> save(@RequestBody ConfigCommand command,
                                              HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        aiConfig.save(teamId(session), session.userId(), command);
        return config(request, response);
    }

    @DeleteMapping("/config")
    public GeneralResponse<ConfigStatus> clear(HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        aiConfig.clear(teamId(session), session.userId());
        return config(request, response);
    }

    @PostMapping("/config/test")
    public GeneralResponse<ConnectionTestResult> test(@RequestBody(required = false) ConfigCommand command,
                                                       HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        return GeneralResponse.success(aiConfig.test(teamId(session), session.userId(), command));
    }

    @PostMapping("/config/test-ticket")
    public GeneralResponse<AgentTicket> issueTestTicket(@RequestBody(required = false) ConfigCommand command,
                                                         HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        return GeneralResponse.success(tickets.issueConnectionTest(teamId(session), session.userId(), command));
    }

    @PostMapping("/agent-tickets")
    public GeneralResponse<AgentTicket> issueTicket(@RequestBody AgentTicketRequest requestBody,
                                                     HttpServletRequest request, HttpServletResponse response) {
        ZhaogangSession session = sessions.resolve(request, response);
        if (requestBody == null || requestBody.iterationId() <= 0) {
            throw new IllegalArgumentException("迭代信息不完整");
        }
        return GeneralResponse.success(tickets.issue(teamId(session), session.userId(), requestBody.iterationId(),
                requestBody.projectColumnName(), requestBody.planColumnName()));
    }

    /** 只接受一次性票据；该端点不接受浏览器传入的后端地址。 */
    @PostMapping("/agent-tickets/redeem")
    public GeneralResponse<AgentRedeem> redeem(@RequestBody AgentTicketRedeemRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("本机 Agent 票据不能为空");
        }
        return GeneralResponse.success(tickets.redeem(requestBody.ticket()));
    }

    public record AgentTicketRequest(long iterationId, String projectColumnName, String planColumnName) {
    }

    public record AgentTicketRedeemRequest(String ticket) {
    }

    private long teamId(ZhaogangSession session) {
        return session.teamId() == null ? 0 : session.teamId();
    }
}
