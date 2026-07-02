package com.itwray.iw.starter.rocketmq.config;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.itwray.iw.starter.rocketmq.MQProducerHelper;
import com.itwray.iw.starter.rocketmq.enums.MQConsumeStatusEnum;
import com.itwray.iw.starter.rocketmq.event.LocalMessageEvent;
import com.itwray.iw.starter.rocketmq.web.RocketMQDataDaoHolder;
import com.itwray.iw.starter.rocketmq.web.dao.BaseMqConsumeRecordsDao;
import com.itwray.iw.starter.rocketmq.web.dao.BaseMqProduceRecordsDao;
import com.itwray.iw.starter.rocketmq.web.entity.BaseMqConsumeRecordsEntity;
import com.itwray.iw.starter.rocketmq.web.mapper.BaseMqConsumeRecordsMapper;
import com.itwray.iw.web.model.dto.UserDto;
import com.itwray.iw.web.utils.UserCurrentGroupUtils;
import com.itwray.iw.web.utils.UserSharedQueryUtils;
import com.itwray.iw.web.utils.UserUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local message auto-configuration.
 * <p>
 * The class name is kept for migration compatibility. It configures an
 * in-process event dispatcher instead of RocketMQ clients.
 * </p>
 *
 * @author wray
 * @since 2024/10/14
 */
@Slf4j
@Configuration
public class RocketMQAutoConfiguration implements ApplicationContextAware {

    private final List<ListenerRegistration> registrations = new ArrayList<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        MQProducerHelper.setApplicationEventPublisher(applicationContext);
        Map<String, RocketMQClientListener> listenerMap = applicationContext.getBeansOfType(RocketMQClientListener.class);
        listenerMap.values().forEach(listener -> {
            Class<?> targetClass = AopUtils.getTargetClass(listener);
            LocalMessageListener annotation = AnnotationUtils.findAnnotation(targetClass, LocalMessageListener.class);
            if (annotation != null) {
                registrations.add(new ListenerRegistration(listener, annotation));
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessage(LocalMessageEvent event) {
        for (ListenerRegistration registration : registrations) {
            if (registration.matches(event)) {
                registration.consume(event);
            }
        }
    }

    @Configuration
    @ConditionalOnBean(MybatisPlusAutoConfiguration.class)
    @MapperScan(basePackageClasses = BaseMqConsumeRecordsMapper.class)
    @Import({BaseMqConsumeRecordsDao.class, BaseMqProduceRecordsDao.class})
    public static class RocketMQDataDaoConfiguration {

        private final ApplicationContext applicationContext;

        @Autowired
        public RocketMQDataDaoConfiguration(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @PostConstruct
        public void init() {
            BaseMqConsumeRecordsDao baseMqConsumeRecordsDao = applicationContext.getBean(BaseMqConsumeRecordsDao.class);
            RocketMQDataDaoHolder.setBaseMqConsumeRecordsDao(baseMqConsumeRecordsDao);

            BaseMqProduceRecordsDao baseMqProduceRecordsDao = applicationContext.getBean(BaseMqProduceRecordsDao.class);
            RocketMQDataDaoHolder.setBaseMqProduceRecordsDao(baseMqProduceRecordsDao);

            String applicationName = applicationContext.getEnvironment().getProperty("spring.application.name");
            RocketMQDataDaoHolder.setApplicationName(applicationName);
        }
    }

    private record ListenerRegistration(RocketMQClientListener listener, LocalMessageListener annotation) {

        boolean matches(LocalMessageEvent event) {
            return Objects.equals(annotation.topic(), event.getTopic())
                    && ("*".equals(annotation.tag()) || Objects.equals(annotation.tag(), event.getTag()));
        }

        void consume(LocalMessageEvent event) {
            ThreadContextSnapshot callerContext = ThreadContextSnapshot.capture();
            Long consumeRecordId = null;
            boolean success = false;
            try {
                ThreadContextSnapshot.clear();
                Object body = event.getBody();
                if (body instanceof UserDto userDto) {
                    UserUtils.setUserId(userDto.getUserId());
                }
                consumeRecordId = addConsumeRecord(event);
                listener.doConsume(body);
                success = true;
                log.info("本地消息消费成功, topic: {}, tag: {}, messageId: {}", event.getTopic(), event.getTag(), event.getMessageId());
            } catch (Exception e) {
                log.error("本地消息消费失败, topic: {}, tag: {}, messageId: {}", event.getTopic(), event.getTag(), event.getMessageId(), e);
            } finally {
                updateConsumeStatus(consumeRecordId, success);
                callerContext.restore();
            }
        }

        private Long addConsumeRecord(LocalMessageEvent event) {
            BaseMqConsumeRecordsDao baseMqConsumeRecordsDao = RocketMQDataDaoHolder.getBaseMqConsumeRecordsDao();
            if (baseMqConsumeRecordsDao == null) {
                return null;
            }
            BaseMqConsumeRecordsEntity entity = new BaseMqConsumeRecordsEntity();
            entity.setServiceName(RocketMQDataDaoHolder.getApplicationName());
            entity.setMessageId(event.getMessageId());
            entity.setVersion("local");
            entity.setTopic(event.getTopic());
            entity.setTag(event.getTag());
            entity.setBody(JSONUtil.toJsonStr(event.getBody()));
            entity.setStatus(MQConsumeStatusEnum.WAIT);
            entity.setCreateTime(LocalDateTime.now());
            if (event.getBody() instanceof UserDto userDto) {
                entity.setUserId(userDto.getUserId());
            }
            baseMqConsumeRecordsDao.save(entity);
            return entity.getId();
        }

        private void updateConsumeStatus(Long id, boolean success) {
            if (id == null) {
                return;
            }
            BaseMqConsumeRecordsDao baseMqConsumeRecordsDao = RocketMQDataDaoHolder.getBaseMqConsumeRecordsDao();
            if (baseMqConsumeRecordsDao == null) {
                return;
            }
            baseMqConsumeRecordsDao.lambdaUpdate()
                    .eq(BaseMqConsumeRecordsEntity::getId, id)
                    .set(BaseMqConsumeRecordsEntity::getStatus, MQConsumeStatusEnum.of(success))
                    .update();
        }
    }

    private record ThreadContextSnapshot(
            UserUtils.UserContextSnapshot userContext,
            UserSharedQueryUtils.UserSharedQueryContextSnapshot sharedQueryContext,
            Integer currentGroupId
    ) {

        static ThreadContextSnapshot capture() {
            return new ThreadContextSnapshot(
                    UserUtils.snapshotContext(),
                    UserSharedQueryUtils.snapshotContext(),
                    UserCurrentGroupUtils.snapshotContext()
            );
        }

        static void clear() {
            UserUtils.clearContext();
            UserSharedQueryUtils.clearContext();
            UserCurrentGroupUtils.clearContext();
        }

        void restore() {
            UserUtils.restoreContext(userContext);
            UserSharedQueryUtils.restoreContext(sharedQueryContext);
            UserCurrentGroupUtils.restoreContext(currentGroupId);
        }
    }
}
