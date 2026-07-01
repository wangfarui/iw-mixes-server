package com.itwray.iw.external.controller;

import com.itwray.iw.auth.model.vo.WebsiteNavigationListVo;
import com.itwray.iw.common.GeneralResponse;
import com.itwray.iw.external.model.dto.GetExchangeRateDto;
import com.itwray.iw.external.model.dto.IpLookupQueryDto;
import com.itwray.iw.external.model.vo.GetExchangeRateVo;
import com.itwray.iw.external.model.vo.IpLookupResultVo;
import com.itwray.iw.external.service.ExternalApiService;
import com.itwray.iw.external.service.InternalApiService;
import com.itwray.iw.web.annotation.SkipWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 外部API的接口控制层
 *
 * @author wray
 * @since 2024/10/17
 */
@RestController
@RequestMapping("/external-service/api")
@Validated
@Tag(name = "外部API接口")
@SkipWrapper
public class ExternalApiController {

    private final ExternalApiService externalApiService;

    private final InternalApiService internalApiService;

    @Autowired
    public ExternalApiController(ExternalApiService externalApiService,
                                 InternalApiService internalApiService) {
        this.externalApiService = externalApiService;
        this.internalApiService = internalApiService;
    }

    @GetMapping("/heartbeat")
    @Operation(summary = "心跳接口")
    public void heartbeat() {
        externalApiService.heartbeat();
    }

    @GetMapping("/getWeather")
    @Operation(summary = "查询天气")
    public Map<Object, Object> getWeather() {
        return externalApiService.getWeather();
    }

    @GetMapping("/ip-lookup/current")
    @Operation(summary = "查询当前请求IP解析定位")
    public GeneralResponse<IpLookupResultVo> getCurrentIpLookup() {
        return GeneralResponse.success(externalApiService.getCurrentIpLookup());
    }

    @PostMapping("/ip-lookup/query")
    @Operation(summary = "查询公网IP或域名解析定位")
    public GeneralResponse<IpLookupResultVo> queryIpLookup(@RequestBody @Valid IpLookupQueryDto dto) {
        return GeneralResponse.success(externalApiService.queryIpLookup(dto));
    }

    @GetMapping("/system/getWeather")
    @Operation(summary = "系统查询天气")
    public GeneralResponse<Map<Object, Object>> getWeatherBySystem() {
        return GeneralResponse.success(externalApiService.getWeather());
    }

    @PostMapping("/getMonitors")
    @Operation(summary = "查询站点监测情况")
    public Map<Object, Object> getMonitors(@RequestBody Map<String, Object> bodyParam) {
        return externalApiService.getMonitorsByUptimeRobot(bodyParam);
    }

    @GetMapping("/getDailyHot/{source}")
    @Operation(summary = "查询每日热点")
    public Map<Object, Object> getDailyHot(@PathVariable String source) {
        return externalApiService.getDailyHot(source);
    }

    @GetMapping("/websiteList")
    @Operation(summary = "查询共享网站列表")
    public List<WebsiteNavigationListVo> websiteList() {
        return externalApiService.querySharedWebsiteList();
    }

    @PostMapping("/exchange-rate/convert")
    @Operation(summary = "公开查询汇率")
    public GeneralResponse<GetExchangeRateVo> exchangeRateConvert(@RequestBody @Valid GetExchangeRateDto dto) {
        return GeneralResponse.success(internalApiService.getExchangeRate(dto));
    }

}
