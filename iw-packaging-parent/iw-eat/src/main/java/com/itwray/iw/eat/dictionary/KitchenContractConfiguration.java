package com.itwray.iw.eat.dictionary;

import com.itwray.iw.eat.mapper.KitchenSnapshotMapper;
import com.itwray.iw.web.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Old fridge clients must not write legacy codes against the snapshot model. */
@Configuration
public class KitchenContractConfiguration implements WebMvcConfigurer {
    private final KitchenSnapshotMapper snapshots;
    public KitchenContractConfiguration(KitchenSnapshotMapper snapshots) { this.snapshots = snapshots; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equals(request.getMethod())) return true;
                if (!"1".equals(request.getHeader("X-IW-Kitchen-Contract")))
                    throw new BusinessException("厨房功能已更新，请重新进入小程序后重试");
                try {
                    if (!"READY".equals(snapshots.migrationPhase()))
                        throw new BusinessException("厨房功能升级中，请稍后重试");
                } catch (org.springframework.dao.DataAccessException unavailable) {
                    throw new BusinessException("厨房功能升级中，请稍后重试");
                }
                return true;
            }
        }).addPathPatterns("/fridge/food/**");
    }
}
