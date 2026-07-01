package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络诊断查询 DTO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Schema(name = "网络诊断查询DTO")
public class NetworkDiagnosticsCheckDto {

    @Schema(title = "诊断目标", description = "公网域名、URL或公网IP")
    @NotBlank(message = "请输入公网域名、URL或公网IP")
    @Size(max = 512, message = "诊断目标不能超过512个字符")
    private String target;

    @Schema(title = "是否执行延迟测试")
    private Boolean latencyEnabled = true;

    @Schema(title = "是否执行DNS查询")
    private Boolean dnsEnabled = true;

    @Schema(title = "是否查看响应头")
    private Boolean headersEnabled = true;

    @Schema(title = "DNS记录类型")
    private List<String> dnsRecordTypes = new ArrayList<>(List.of("A", "AAAA", "CNAME", "MX", "TXT", "NS"));

    @Schema(title = "延迟测试次数")
    private Integer probeCount = 3;

    @Schema(title = "单次超时时间，单位毫秒")
    private Integer timeoutMs = 3000;
}
