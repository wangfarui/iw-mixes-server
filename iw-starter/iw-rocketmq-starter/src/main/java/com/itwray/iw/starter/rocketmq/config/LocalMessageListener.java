package com.itwray.iw.starter.rocketmq.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as a local message consumer.
 *
 * @author wray
 * @since 2026/6/27
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LocalMessageListener {

    String consumerGroup() default "";

    String topic();

    String tag() default "*";
}
