package com.itwray.iw.external.zhaogang;

import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Context;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.Month;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModels.UpdateDayCommand;
import com.itwray.iw.external.zhaogang.calendar.WorkCalendarModule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/external-service/api/zhaogang/calendar")
@Validated
@Tag(name = "找钢工作日历")
public class ZhaogangCalendarController {

    private final ZhaogangSessionManager sessionManager;
    private final WorkCalendarModule calendarModule;

    public ZhaogangCalendarController(ZhaogangSessionManager sessionManager, WorkCalendarModule calendarModule) {
        this.sessionManager = sessionManager;
        this.calendarModule = calendarModule;
    }

    @GetMapping
    @Operation(summary = "查询月份工作日历")
    public GeneralResponse<Month> month(@RequestParam @NotBlank String month,
                                        HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(calendarModule.month(context(sessionManager.resolve(request, response)), month));
    }

    @PutMapping("/days/{date}")
    @Operation(summary = "设置日期为工作日或休息日")
    public GeneralResponse<Month> updateDay(@PathVariable String date,
                                            @Valid @RequestBody UpdateDayCommand command,
                                            HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(calendarModule.updateDay(
                context(sessionManager.resolve(request, response)), date, command));
    }

    @DeleteMapping("/days/{date}")
    @Operation(summary = "恢复日期默认状态")
    public GeneralResponse<Month> resetDay(@PathVariable String date,
                                           HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(calendarModule.resetDay(context(sessionManager.resolve(request, response)), date));
    }

    @PutMapping("/leaves/{date}")
    @Operation(summary = "设置或取消个人请假日")
    public GeneralResponse<Month> updateLeave(@PathVariable String date,
                                               @RequestParam(defaultValue = "true") boolean leave,
                                               HttpServletRequest request, HttpServletResponse response) {
        return GeneralResponse.success(calendarModule.updateLeave(
                context(sessionManager.resolve(request, response)), date, leave));
    }

    private Context context(ZhaogangSession session) {
        return new Context(session.userId(), session.userName(), session.teamId() == null ? 0 : session.teamId());
    }
}
