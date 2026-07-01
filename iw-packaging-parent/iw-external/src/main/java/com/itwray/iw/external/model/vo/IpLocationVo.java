package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * IP定位信息 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "IP定位信息VO")
public class IpLocationVo {

    @Schema(title = "定位服务商")
    private String provider;

    @Schema(title = "服务商状态")
    private String status;

    @Schema(title = "服务商消息")
    private String info;

    @Schema(title = "国家")
    private String country;

    @Schema(title = "省份")
    private String province;

    @Schema(title = "城市")
    private String city;

    @Schema(title = "行政区划编码")
    private String adcode;

    @Schema(title = "定位矩形范围")
    private String rectangle;

    @Schema(title = "服务商原始响应")
    private Map<String, Object> raw;
}
