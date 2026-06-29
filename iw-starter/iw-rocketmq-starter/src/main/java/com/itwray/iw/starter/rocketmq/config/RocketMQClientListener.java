package com.itwray.iw.starter.rocketmq.config;

/**
 * Local message listener contract.
 * <p>
 * The interface name is kept for migration compatibility. Implementations are
 * discovered through {@link LocalMessageListener}.
 * </p>
 *
 * @author wray
 * @since 2024/10/14
 */
public interface RocketMQClientListener<T> {

    Class<T> getGenericClass();

    void doConsume(T t);
}
