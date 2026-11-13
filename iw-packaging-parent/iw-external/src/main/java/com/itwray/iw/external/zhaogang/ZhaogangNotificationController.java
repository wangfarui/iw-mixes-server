package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.notification.WorklogNotificationModule;
import com.itwray.iw.external.zhaogang.notification.WorklogNotificationModule.*;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/external-service/api/zhaogang/teams/{teamId}/worklog-notification")
public class ZhaogangNotificationController {
    private final ZhaogangSessionManager sessions;
    private final ZhaogangProperties properties;
    private final WorklogNotificationModule module;
    public ZhaogangNotificationController(ZhaogangSessionManager sessions,ZhaogangProperties properties,WorklogNotificationModule module) {
        this.sessions=sessions; this.properties=properties; this.module=module;
    }
    @GetMapping public GeneralResponse<Status> status(@PathVariable long teamId,HttpServletRequest request,HttpServletResponse response) {
        return GeneralResponse.success(module.status(actor(request,response),teamId));
    }
    @PutMapping public GeneralResponse<Status> save(@PathVariable long teamId,@RequestBody Command command,HttpServletRequest request,HttpServletResponse response) {
        return GeneralResponse.success(module.save(actor(request,response),teamId,command));
    }
    @PostMapping("/test") public GeneralResponse<Status> test(@PathVariable long teamId,@RequestBody TestCommand command,HttpServletRequest request,HttpServletResponse response) {
        return GeneralResponse.success(module.test(actor(request,response),teamId,command.revision()));
    }
    @PostMapping("/send-now") public GeneralResponse<Status> sendNow(@PathVariable long teamId,@RequestBody SendNowCommand command,HttpServletRequest request,HttpServletResponse response) {
        return GeneralResponse.success(module.sendNow(actor(request,response),teamId,command));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    GeneralResponse<?> invalidBody() {
        // Conversion exception text can contain a pasted Webhook; never delegate it to generic body logs.
        return GeneralResponse.fail("通知配置请求格式不正确，请检查后重试");
    }
    private Actor actor(HttpServletRequest request,HttpServletResponse response) {
        var session=sessions.resolve(request,response);
        return new Actor(session.userId(),session.userName(),session.avatar(),session.teamId()==null?0:session.teamId(),session.team(),properties.configuredTeamHost());
    }
}
