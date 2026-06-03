package com.example.flashsale.config;

import com.example.flashsale.interceptor.AccessControlInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AccessControlInterceptor accessControlInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessControlInterceptor)
                .addPathPatterns("/api/**"); // Apply to all API routes
    }
}
