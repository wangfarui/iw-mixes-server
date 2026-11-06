package com.itwray.iw.external.zhaogang;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 找钢工作台的固定 CODING 连接配置。
 */
@Data
@ConfigurationProperties(prefix = "iw.external.zhaogang")
public class ZhaogangProperties {

    private String team = "g-iijw5014";

    private String apiUrl = "https://g-iijw5014.coding.net/open-api";

    /** 复用已有内部密钥作为 Cookie 加密材料，避免增加生产配置项。 */
    private String sessionKey = "dev-secret";

    private int sessionDays = 30;

    private int tokenDays = 365;

    private int requestTimeoutMs = 15000;

    /** 目录元数据刷新周期，也是非强制最近构建同步的逐计划节流周期。 */
    private int catalogRefreshMinutes = 10;

    /** 超过此时间未访问的目录不再参与定时刷新。 */
    private int catalogActiveMinutes = 60;

    /** 超过此时间未访问的内存目录会被清理。 */
    private int catalogRetentionMinutes = 120;

    /** 所有用户共享的 CODING 目录和最近构建请求并发上限。 */
    private int catalogConcurrency = 4;

    /** 单个 CODING 个人令牌允许的最大并发数。 */
    private int codingConcurrencyPerToken = 4;

    /** 单个 CODING 个人令牌的事项读取速率上限，0 表示不限制。 */
    private int codingIssueQpsPerToken = 25;

    /** 事项批量读取的进程级调度线程数，不代表 CODING 并发数。 */
    private int codingIssueExecutorConcurrency = 32;

    /** 提交读取 Action 的全局 QPS（跨所有 PAT），0 表示不限制。 */
    private int codeCommitQps = 25;

    /** diff 读取 Action 的全局 QPS（跨所有 PAT），0 表示不限制。 */
    private int codeDiffQps = 25;

    /** 代码扫描进程内并发任务数。 */
    private int codeScanConcurrency = 2;

    /** 代码扫描待执行队列容量。 */
    private int codeScanQueueCapacity = 8;

    /** 扫描任务心跳超时时间。 */
    private int codeScanHeartbeatTimeoutSeconds = 300;

    /** 提交分页大小。 */
    private int codeCommitPageSize = 100;

    /** 单分支单月最大分页数。 */
    private int codeMaxPagesPerBranch = 100;

    /** 单仓库分支保护上限。 */
    private int codeMaxBranchesPerRepo = 2000;

    /** 单次 diff 响应大小上限。 */
    private long codeDiffMaxBytes = 5L * 1024 * 1024;

    /** diff 请求超时时间。 */
    private int codeDiffTimeoutMs = 30000;

    /** 仅在用户发起定向补算时，允许通过只读 HTTPS Git 获取缺失行数。 */
    private boolean codeGitLineStatsEnabled = true;

    private int codeGitLineStatsTimeoutSeconds = 40;

    private long codeGitLineStatsMaxBytes = 128L * 1024 * 1024;

    /** 代码统计查询缓存秒数。 */
    private int codeScanCacheSeconds = 60;

    /** CODING 成员 profile 缓存小时数。 */
    private int codeProfileCacheHours = 12;

    /** 提交缓存保留自然月数。 */
    private int codeStatsRetentionMonths = 24;

    /** 扫描任务/检查点保留自然月数。 */
    private int codeScanJobRetentionMonths = 6;

    /** 当前月自动增量刷新间隔。 */
    private int codeCurrentMonthRefreshMinutes = 180;

    /** 持续同步首次回补下界；查看月份不会更改此范围。 */
    private String codeSyncFrom = "2026-01-01";

    private boolean codeSyncEnabled = true;

    /** 当前月手动重扫冷却秒数。 */
    private int codeCurrentMonthCooldownSeconds = 600;

    /** 历史月手动重扫冷却秒数。 */
    private int codeHistoricalCooldownSeconds = 86400;

    /** 失败/部分/中断/取消后的重试冷却秒数。 */
    private int codeFailedRetrySeconds = 600;

    /** 同一用户手动重扫频率秒数。 */
    private int codeUserCooldownSeconds = 300;

    /** CODING 事项快照缓存有效期。 */
    private int codingIssueCacheSeconds = 30;

    /** 工时聚合结果的 Redis 缓存有效期，默认 1 分钟。 */
    private int worklogCacheSeconds = 60;

    /** 团队工时成员查询的进程级并发线程数。 */
    private int worklogExecutorConcurrency = 8;

    /** 当前唯一允许维护工作日历的 CODING 用户。 */
    private long calendarManagerUserId = 9292850L;

    /** 复用现有部署环境标记，生产 Cookie 自动带 Secure。 */
    private String webEnv = "dev";

    private String agentBackendUrl = "https://api.itwray.com";

    private String aiDefaultModel = "gpt-5.6-terra";

    private int aiConnectTimeoutMs = 5000;

    private int aiRequestTimeoutMs = 60000;

    private long aiMaxImageBytes = 10 * 1024 * 1024;

    private int releaseImportTaskTtlSeconds = 900;

    private int releaseImportTaskConcurrency = 2;

    public String getAgentBackendUrl() {
        return StringUtils.defaultIfBlank(agentBackendUrl, "https://api.itwray.com");
    }

    public String safeApiUrl() {
        return StringUtils.defaultIfBlank(apiUrl, "https://g-iijw5014.coding.net/open-api");
    }

    public String configuredTeamHost() {
        return "https://" + StringUtils.defaultIfBlank(team, "g-iijw5014") + ".coding.net";
    }

    public String safeSessionKey() {
        return StringUtils.defaultIfBlank(sessionKey, "dev-secret");
    }

    public boolean isProduction() {
        return "prod".equalsIgnoreCase(webEnv) || "production".equalsIgnoreCase(webEnv);
    }
}
