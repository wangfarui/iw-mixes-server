package com.itwray.iw.points.controller;

import com.itwray.iw.points.model.dto.plan.PointsTaskPlanAddDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanGenerateDto;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanGenerateVo;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPageDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateStatusDto;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanDetailVo;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanPageVo;
import com.itwray.iw.points.service.PointsTaskPlanService;
import com.itwray.iw.points.service.PointsTaskPlanCalendar;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanScheduleDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPreviewDto;
import java.util.List;
import com.itwray.iw.web.controller.WebController;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.web.exception.AuthorizedException;
import com.itwray.iw.web.model.vo.PageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 任务计划表 接口控制层
 *
 * @author wray
 * @since 2025-05-07
 */
@RestController
@RequestMapping("/points/task/plan")
@Validated
@Tag(name = "任务计划表接口")
public class PointsTaskPlanController extends WebController<PointsTaskPlanService,
        PointsTaskPlanAddDto, PointsTaskPlanUpdateDto, PointsTaskPlanDetailVo, Integer> {

    @Autowired
    public PointsTaskPlanController(PointsTaskPlanService webService) {
        super(webService);
    }

    @Autowired
    private PointsTaskPlanCalendar calendar;

    @GetMapping("/calendar")
    @Operation(summary = "查询农历年份的真实月份及天数")
    public List<PointsTaskPlanCalendar.Month> calendar(@RequestParam int year) {
        requireLogin();
        return calendar.months(year);
    }

    @PostMapping("/convertDate")
    @Operation(summary = "公历农历日期转换，不保存计划")
    public PointsTaskPlanCalendar.DateSelection convertDate(
            @RequestBody PointsTaskPlanScheduleDto dto) {
        requireLogin();
        return calendar.selection(calendar.requestedDate(dto));
    }

    @PostMapping("/preview")
    @Operation(summary = "预览未来三次计划，不生成任务")
    public PointsTaskPlanCalendar.Preview preview(
            @RequestBody PointsTaskPlanPreviewDto dto) {
        requireLogin();
        return getWebService().preview(dto);
    }

    private void requireLogin() {
        String token = UserUtils.getToken(false);
        if (token == null || token.isBlank()) { throw new AuthorizedException("当前未登录，请先登录"); }
        UserUtils.getUserId();
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询任务计划")
    public PageVo<PointsTaskPlanPageVo> page(@RequestBody @Valid PointsTaskPlanPageDto dto) {
        return getWebService().page(dto);
    }

    @PutMapping("/updateStatus")
    @Operation(summary = "更新任务状态")
    public void updatePlanStatus(@RequestBody @Valid PointsTaskPlanUpdateStatusDto dto) {
        getWebService().updatePlanStatus(dto);
    }

    @PostMapping("/generateNow")
    @Operation(summary = "按今天的计划日期额外生成待办，不推进原计划")
    public PointsTaskPlanGenerateVo generateNow(@RequestBody @Valid PointsTaskPlanGenerateDto dto) {
        return getWebService().generateNow(dto);
    }
}
