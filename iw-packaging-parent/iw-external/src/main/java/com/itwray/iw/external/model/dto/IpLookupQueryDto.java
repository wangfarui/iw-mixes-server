package com.itwray.iw.external.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * IP地址解析查询 DTO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@Schema(name = "IP地址解析查询DTO")
public class IpLookupQueryDto {

    @Schema(title = "查询内容", description = "公网IP、域名或URL")
    @NotBlank(message = "请输入公网IP、域名或URL")
    @Size(max = 512, message = "查询内容不能超过512个字符")
    private String input;

    @Schema(title = "查询模式", description = "auto/ip/domain")
    private String mode = "auto";
}
