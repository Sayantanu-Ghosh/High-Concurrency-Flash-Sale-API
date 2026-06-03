package com.example.flashsale.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation for rate limiting API endpoints.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    /**
     * The time window in seconds.
     */
    int seconds() default 60;

    /**
     * The maximum number of requests allowed within the time window.
     */
    int maxCount() default 100;
}
