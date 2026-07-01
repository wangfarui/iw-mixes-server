package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IP地址解析记录 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "IP地址解析记录VO")
public class IpLookupRecordVo {

    @Schema(title = "解析主机")
    private String host;

    @Schema(title = "IP地址")
    private String ip;

    @Schema(title = "IP协议族")
    private String family;

    @Schema(title = "是否公网IP")
    private boolean publicIp;

    @Schema(title = "地址类型")
    private String addressType;

    @Schema(title = "定位信息")
    private IpLocationVo location;

    @Schema(title = "记录提示")
    private String message;
}
