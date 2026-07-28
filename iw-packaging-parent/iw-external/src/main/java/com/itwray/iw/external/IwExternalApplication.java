package com.itwray.iw.external;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IW外部服务应用程序
 *
 * @author wray
 * @since 2024/3/2
 */
@SpringBootApplication
@EnableScheduling
public class IwExternalApplication {

    public static void main(String[] args) {
        SpringApplication.run(IwExternalApplication.class, args);
    }
}
