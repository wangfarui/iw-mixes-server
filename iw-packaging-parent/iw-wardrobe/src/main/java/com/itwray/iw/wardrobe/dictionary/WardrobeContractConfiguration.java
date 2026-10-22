package com.itwray.iw.wardrobe.dictionary;

import com.itwray.iw.web.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Short-lived old sessions must not submit encoded values as new dictionary sources. */
@Configuration
public class WardrobeContractConfiguration implements WebMvcConfigurer {
    private final com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper snapshots;
    public WardrobeContractConfiguration(com.itwray.iw.wardrobe.mapper.WardrobeSnapshotMapper snapshots) { this.snapshots = snapshots; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equals(request.getMethod())) return true;
                if (!"2".equals(request.getHeader("X-IW-Wardrobe-Contract")))
                    throw new BusinessException("衣柜功能已更新，请重新进入小程序后重试");
                try {
                    if (!"READY".equals(snapshots.migrationPhase())) throw new BusinessException("衣柜功能升级中，请稍后重试");
                } catch (org.springframework.dao.DataAccessException unavailable) {
                    throw new BusinessException("衣柜功能升级中，请稍后重试");
                }
                return true;
            }
        }).addPathPatterns("/wardrobe/**");
    }
}
