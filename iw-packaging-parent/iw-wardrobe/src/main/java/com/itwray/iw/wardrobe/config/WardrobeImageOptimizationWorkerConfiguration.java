package com.itwray.iw.wardrobe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WardrobeImageOptimizationWorkerConfiguration {

    @Bean("wardrobeImageOptimizationExecutor")
    public ThreadPoolTaskExecutor wardrobeImageOptimizationExecutor(
            @Value("${iw.wardrobe.image-optimization.worker-concurrency:1}") int concurrency) {
        int size = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("wardrobe-image-optimization-");
        executor.initialize();
        return executor;
    }
}
