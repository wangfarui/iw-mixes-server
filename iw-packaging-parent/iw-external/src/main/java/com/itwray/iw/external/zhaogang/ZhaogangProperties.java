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
