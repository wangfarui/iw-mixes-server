package com.itwray.iw.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IW datasource properties.
 *
 * @author wray
 * @since 2026/6/29
 */
@Data
@ConfigurationProperties(prefix = "iw.db")
public class IwDbProperties {

    private String driverClass = "com.mysql.cj.jdbc.Driver";

    private String url;

    private String username;

    private String password;
}
