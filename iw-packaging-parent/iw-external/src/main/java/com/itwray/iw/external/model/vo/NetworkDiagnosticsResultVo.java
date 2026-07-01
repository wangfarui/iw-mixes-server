package com.itwray.iw.external.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络诊断结果 VO。
 *
 * @author wray
 * @since 2026/7/1
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "网络诊断结果VO")
public class NetworkDiagnosticsResultVo {

    @Schema(title = "原始输入")
    private String input;

    @Schema(title = "规范化目标")
    private String normalizedTarget;

    @Schema(title = "目标主机")
    private String host;

    @Schema(title = "协议")
    private String scheme;

    @Schema(title = "端口")
    private Integer port;

    @Schema(title = "目标类型")
    private String targetType;

    @Schema(title = "查询视角")
    private String queryPerspective;

    @Schema(title = "是否登录用户")
    private Boolean authenticated;

    @Schema(title = "查询时间")
    private OffsetDateTime checkedAt;

    @Schema(title = "总耗时，单位毫秒")
    private Long durationMs;

    @Schema(title = "诊断摘要")
    private String summary;

    @Schema(title = "整体是否成功")
    private Boolean success;

    @Schema(title = "额度信息")
    private Quota quota;

    @Schema(title = "延迟测试结果")
    private Latency latency;

    @Schema(title = "DNS查询结果")
    private Dns dns;

    @Schema(title = "响应头结果")
    private Headers headers;

    @Schema(title = "提示信息")
    private List<String> warnings = new ArrayList<>();

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Quota {

        private String scope;

        private Integer dailyLimit;

        private Integer dailyUsed;

        private Integer minuteLimit;

        private Integer minuteUsed;

        private Integer totalDailyLimit;

        private Integer totalDailyUsed;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Latency {

        private Boolean success;

        private Integer probeCount;

        private Integer successCount;

        private Integer failureCount;

        private Long minMs;

        private Long avgMs;

        private Long maxMs;

        private Long jitterMs;

        private List<LatencyAttempt> attempts = new ArrayList<>();

        private List<String> warnings = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LatencyAttempt {

        private Integer index;

        private Boolean success;

        private Integer statusCode;

        private Long durationMs;

        private String finalUrl;

        private String error;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Dns {

        private Boolean success;

        private Long durationMs;

        private List<String> recordTypes = new ArrayList<>();

        private List<DnsRecord> records = new ArrayList<>();

        private List<String> warnings = new ArrayList<>();

        private String error;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DnsRecord {

        private String type;

        private String name;

        private String value;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Headers {

        private Boolean success;

        private Integer statusCode;

        private Long durationMs;

        private String finalUrl;

        private List<Redirect> redirects = new ArrayList<>();

        private Map<String, List<String>> responseHeaders = new LinkedHashMap<>();

        private List<String> warnings = new ArrayList<>();

        private String error;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Redirect {

        private String fromUrl;

        private String toUrl;

        private Integer statusCode;

        private Long durationMs;
    }
}
