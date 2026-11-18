package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.mapper.ZhaogangWorkbenchProfileMapper;
import com.itwray.iw.external.zhaogang.profile.WorkbenchMobile;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/external-service/api/zhaogang/profile/mobile")
public class ZhaogangProfileController {
    private final ZhaogangSessionManager sessions;
    private final ZhaogangWorkbenchProfileMapper profiles;
    public ZhaogangProfileController(ZhaogangSessionManager sessions, ZhaogangWorkbenchProfileMapper profiles) {
        this.sessions = sessions;
        this.profiles = profiles;
    }
    @GetMapping
    @Operation(summary = "查询当前用户的 Dev Hub 手机号")
    public GeneralResponse<WorkbenchMobile.Preference> get(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var session = identity(request, response);
        return GeneralResponse.success(new WorkbenchMobile.Preference(profiles.findMobile(session.teamId(), session.userId())));
    }
    @PutMapping
    @Operation(summary = "更新当前用户的 Dev Hub 手机号")
    public GeneralResponse<WorkbenchMobile.Preference> update(@RequestBody WorkbenchMobile.Update command,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var session = identity(request, response);
        String mobile = WorkbenchMobile.normalize(command.mobile());
        profiles.saveMobile(session.teamId(), session.userId(), mobile);
        return GeneralResponse.success(new WorkbenchMobile.Preference(mobile));
    }
    private ZhaogangSession identity(HttpServletRequest request, HttpServletResponse response) {
        var session = sessions.resolve(request, response);
        if (session.teamId() == null || session.teamId() <= 0 || session.userId() == null || session.userId() <= 0)
            throw new ZhaogangSessionException("当前 CODING 身份不完整，请重新进入 Dev Hub");
        return session;
    }

}
