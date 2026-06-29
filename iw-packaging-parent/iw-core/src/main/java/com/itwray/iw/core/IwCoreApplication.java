package com.itwray.iw.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IW core aggregated service.
 *
 * @author wray
 * @since 2026/6/27
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.itwray.iw")
public class IwCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(IwCoreApplication.class, args);
    }
}
