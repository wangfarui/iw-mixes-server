package com.itwray.iw.core.localgateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Local gateway compatibility configuration.
 *
 * @author wray
 * @since 2026/6/29
 */
@Component
@ConfigurationProperties(prefix = "iw.local-gateway")
public class LocalGatewayProperties {

    private boolean enabled;

    private String externalBaseUrl = "http://127.0.0.1:18006";

    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExternalBaseUrl() {
        return externalBaseUrl;
    }

    public void setExternalBaseUrl(String externalBaseUrl) {
        this.externalBaseUrl = externalBaseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
