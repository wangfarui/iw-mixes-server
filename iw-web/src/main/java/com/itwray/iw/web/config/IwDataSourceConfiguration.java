package com.itwray.iw.web.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * Creates the application datasource from project-level iw.db properties.
 *
 * @author wray
 * @since 2026/6/29
 */
@Configuration
public class IwDataSourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(IwDbProperties dbProperties) {
        if (!StringUtils.hasText(dbProperties.getUrl())) {
            throw new IllegalStateException("缺少数据库配置 iw.db.url");
        }

        HikariDataSource dataSource = new HikariDataSource();
        if (StringUtils.hasText(dbProperties.getDriverClass())) {
            dataSource.setDriverClassName(dbProperties.getDriverClass());
        }
        dataSource.setJdbcUrl(dbProperties.getUrl());
        dataSource.setUsername(dbProperties.getUsername());
        dataSource.setPassword(dbProperties.getPassword());
        return dataSource;
    }
}
