package com.itwray.iw.starter.rocketmq;

import cn.hutool.json.JSONUtil;
import com.itwray.iw.starter.rocketmq.event.LocalMessageEvent;
import com.itwray.iw.starter.rocketmq.web.RocketMQDataDaoHolder;
import com.itwray.iw.starter.rocketmq.web.dao.BaseMqProduceRecordsDao;
import com.itwray.iw.starter.rocketmq.web.entity.BaseMqProduceRecordsEntity;
import com.itwray.iw.web.model.dto.UserDto;
import com.itwray.iw.web.model.enums.mq.MQDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Local message producer helper.
 * <p>
 * The class name is kept for migration compatibility. It no longer talks to
 * RocketMQ; messages are published as Spring application events and consumed
 * after transaction commit.
 * </p>
 *
 * @author wray
 * @since 2024/10/14
 */
@Slf4j
public abstract class MQProducerHelper {

    private static ApplicationEventPublisher applicationEventPublisher;

    private static final ExecutorService executorService = new ThreadPoolExecutor(
            2,
            10,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        MQProducerHelper.applicationEventPublisher = applicationEventPublisher;
    }

    public static void send(MQDestination destination, Object obj) {
        send(destination.getDestination(), obj);
    }

    public static void send(String destination, Object obj) {
        Assert.notNull(applicationEventPublisher, "ApplicationEventPublisher is null");
        String messageId = UUID.randomUUID().toString();
        recordProductionMessages(destination, obj, messageId);
        applicationEventPublisher.publishEvent(LocalMessageEvent.of(destination, obj, messageId));
        log.info("本地消息发送成功, destination: {}, messageId: {}", destination, messageId);
    }

    public static void asyncSend(String destination, Object obj) {
        executorService.execute(() -> send(destination, obj));
    }

    private static void recordProductionMessages(String destination, Object obj, String messageId) {
        executorService.submit(() -> {
            try {
                BaseMqProduceRecordsDao baseMqProduceRecordsDao = RocketMQDataDaoHolder.getBaseMqProduceRecordsDao();
                if (baseMqProduceRecordsDao == null) {
                    return;
                }
                String[] tempArr = destination.split(":", 2);
                BaseMqProduceRecordsEntity entity = new BaseMqProduceRecordsEntity();
                entity.setServiceName(RocketMQDataDaoHolder.getApplicationName());
                entity.setMessageId(messageId);
                entity.setVersion("local");
                entity.setTopic(tempArr[0]);
                entity.setTag(tempArr.length > 1 ? tempArr[1] : "");
                entity.setBody(JSONUtil.toJsonStr(obj));
                entity.setCreateTime(LocalDateTime.now());
                if (obj instanceof UserDto userDto) {
                    entity.setUserId(userDto.getUserId());
                }
                baseMqProduceRecordsDao.save(entity);
            } catch (Exception e) {
                log.error("异步记录本地消息生产记录异常", e);
            }
        });
    }
}
