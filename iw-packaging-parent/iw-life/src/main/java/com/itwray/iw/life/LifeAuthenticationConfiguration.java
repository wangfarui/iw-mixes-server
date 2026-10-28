package com.itwray.iw.life;

import com.itwray.iw.web.exception.AuthorizedException;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Require authentication even for calendar metadata and reject missing sessions with code 401. */
@Configuration
public class LifeAuthenticationConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String token=UserUtils.getToken(false);
                if(token==null||token.isBlank())throw new AuthorizedException("当前未登录，请先登录");
                UserUtils.getUserId();
                return true;
            }
        }).addPathPatterns("/life/**").order(100); // Run after the shared context-cleanup interceptor.
    }
}
