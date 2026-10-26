package com.itwray.iw.auth.config;

import com.itwray.iw.auth.mapper.KitchenDictionaryMapper;
import com.itwray.iw.web.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Short-lived old sessions must not manage kitchen dictionaries with legacy clients. */
@Configuration
public class KitchenAuthContractConfiguration implements WebMvcConfigurer {
    private final KitchenDictionaryMapper mapper;
    public KitchenAuthContractConfiguration(KitchenDictionaryMapper mapper) { this.mapper = mapper; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equals(request.getMethod())) return true;
                if (!"1".equals(request.getHeader("X-IW-Kitchen-Contract")))
                    throw new BusinessException("厨房功能已更新，请重新进入小程序后重试");
                try {
                    if (!"READY".equals(mapper.migrationPhase()))
                        throw new BusinessException("厨房功能升级中，请稍后重试");
                } catch (org.springframework.dao.DataAccessException unavailable) {
                    throw new BusinessException("厨房功能升级中，请稍后重试");
                }
                return true;
            }
        }).addPathPatterns("/kitchen/**");
    }
}
