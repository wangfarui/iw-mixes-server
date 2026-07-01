package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * IP地址解析结果 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "IP地址解析结果VO")
public class IpLookupResultVo {

    @Schema(title = "原始输入")
    private String input;

    @Schema(title = "规范化输入")
    private String normalizedInput;

    @Schema(title = "目标类型")
    private String targetType;

    @Schema(title = "查询视角")
    private String queryPerspective;

    @Schema(title = "客户端IP")
    private String clientIp;

    @Schema(title = "查询时间")
    private OffsetDateTime queriedAt;

    @Schema(title = "解析记录")
    private List<IpLookupRecordVo> records = new ArrayList<>();

    @Schema(title = "提示信息")
    private List<String> warnings = new ArrayList<>();
}
