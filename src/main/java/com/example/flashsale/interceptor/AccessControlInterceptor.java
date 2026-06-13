package com.example.flashsale.interceptor;

import com.example.flashsale.annotation.RateLimit;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

@Component
public class AccessControlInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AccessControlInterceptor.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);

            if (rateLimit != null) {
                int seconds = rateLimit.seconds();
                int maxCount = rateLimit.maxCount();

                // For simplicity, we use the client's IP address as the key.
                // In a real distributed system, you might use a user ID after authentication.
                String key = "rate_limit:" + request.getRemoteAddr() + ":" + request.getRequestURI();

                try {
                    Long count = redisTemplate.opsForValue().increment(key);

                    if (count != null) {
                        if (count == 1) {
                            // First request in the time window, set the expiration.
                            redisTemplate.expire(key, seconds, TimeUnit.SECONDS);
                        }

                        if (count > maxCount) {
                            logger.warn("Rate limit exceeded for key: {}. Count: {}", key, count);
                            response.setStatus(429);
                            response.setContentType("application/json");
                            try (OutputStream os = response.getOutputStream()) {
                                os.write("{\"message\":\"Too many requests - please try again later.\"}".getBytes());
                                os.flush();
                            }
                            return false;
                        }
                    }
                } catch (Exception e) {
                    // If Redis is down, we can choose to either allow all requests or deny all.
                    // Allowing is safer to not block legitimate users, but carries risk.
                    logger.error("Could not connect to Redis for rate limiting. Allowing request.", e);
                }
            }
        }
        return true;
    }
}
