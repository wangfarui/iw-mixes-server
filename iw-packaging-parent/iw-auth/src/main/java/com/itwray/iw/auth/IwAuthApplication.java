package com.itwray.iw.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IW认证服务应用程序
 *
 * @author wray
 * @since 2024/3/2
 */
@SpringBootApplication
@EnableScheduling
public class IwAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(IwAuthApplication.class, args);
    }
}
