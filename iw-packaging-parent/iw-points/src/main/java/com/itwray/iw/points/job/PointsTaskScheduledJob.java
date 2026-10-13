package com.itwray.iw.points.job;

import com.itwray.iw.common.constants.BoolEnum;
import com.itwray.iw.points.dao.PointsTaskBasicsDao;
import com.itwray.iw.points.dao.PointsTaskBookkeepingDao;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.points.dao.PointsTaskPlanDao;
import com.itwray.iw.points.dao.PointsTaskRelationDao;
import com.itwray.iw.points.model.bo.ExpiredTaskBo;
import com.itwray.iw.points.model.dto.PointsRecordsAddDto;
import com.itwray.iw.points.model.dto.task.PointsTaskRelationAddDto;
import com.itwray.iw.points.model.dto.task.TaskBasicsAddDto;
import com.itwray.iw.points.model.entity.PointsTaskBasicsEntity;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.entity.PointsTaskRelationEntity;
import com.itwray.iw.points.model.enums.PointsSourceTypeEnum;
import com.itwray.iw.points.model.enums.PointsTransactionTypeEnum;
import com.itwray.iw.points.model.enums.TaskStatusEnum;
import com.itwray.iw.points.model.param.QueryExpiredTaskParam;
import com.itwray.iw.points.model.param.QueryPlanTaskParam;
import com.itwray.iw.points.service.PointsTaskBasicsService;
import com.itwray.iw.points.service.PointsTaskRelationService;
import com.itwray.iw.starter.rocketmq.MQProducerHelper;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.model.enums.mq.PointsRecordsTopicEnum;
import com.itwray.iw.web.utils.ApplicationContextHolder;
import com.itwray.iw.web.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.time.ZoneId;

/**
 * 积分任务的定时任务
 *
 * @author wray
 * @since 2025/4/21
 */
@Component
@Slf4j
public class PointsTaskScheduledJob {

    private final PointsTaskBasicsDao pointsTaskBasicsDao;

    private final PointsTaskRelationDao pointsTaskRelationDao;

    private final PointsTaskPlanDao pointsTaskPlanDao;

    private TransactionTemplate transactionTemplate;
    private PointsTaskBookkeepingDao taskBookkeepingDao;

    @Autowired
    public void setTaskBookkeepingDao(PointsTaskBookkeepingDao dao) { this.taskBookkeepingDao = dao; }

    @Autowired
    public PointsTaskScheduledJob(PointsTaskBasicsDao pointsTaskBasicsDao, PointsTaskRelationDao pointsTaskRelationDao,
                                  PointsTaskPlanDao pointsTaskPlanDao) {
        this.pointsTaskBasicsDao = pointsTaskBasicsDao;
        this.pointsTaskRelationDao = pointsTaskRelationDao;
        this.pointsTaskPlanDao = pointsTaskPlanDao;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 每天凌晨0点执行, 处理已过期未执行的任务
     * TODO 前提条件：points服务是单实例
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void handleExpiredTask() {
        long currentTimeMillis = System.currentTimeMillis();
        log.info("Job[handleExpiredTask]任务开始执行, 开始时间: {}", LocalDateTime.now());

        QueryExpiredTaskParam param = new QueryExpiredTaskParam();
        param.setTaskStatus(TaskStatusEnum.WAIT.getCode());
        param.setPunishStatus(BoolEnum.FALSE.getCode());
        param.setDeadlineDate(LocalDate.now());
        while (true) {
            List<ExpiredTaskBo> taskList = pointsTaskBasicsDao.getBaseMapper().queryExpiredTask(param);
            if (taskList.isEmpty()) {
                break;
            }
            for (ExpiredTaskBo bo : taskList) {
                transactionTemplate.execute(action -> {
                    pointsTaskRelationDao.lambdaUpdate()
                            .eq(PointsTaskRelationEntity::getId, bo.getRelationId())
                            .set(PointsTaskRelationEntity::getPunishStatus, BoolEnum.TRUE.getCode())
                            .update();

                    log.info("未完成任务[" + bo.getTaskId() + "]" + bo.getTaskName());

                    if (bo.getPunishPoints() != 0) {
                        PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
                        pointsRecordsAddDto.setTransactionType(PointsTransactionTypeEnum.DEDUCT.getCode());
                        pointsRecordsAddDto.setPoints(-bo.getPunishPoints());
                        pointsRecordsAddDto.setSource("未完成任务[" + bo.getTaskId() + "]" + bo.getTaskName());
                        pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.POINTS_TASK_TIMING.getCode());
                        pointsRecordsAddDto.setUserId(bo.getUserId());
                        MQProducerHelper.send(PointsRecordsTopicEnum.TASK, pointsRecordsAddDto);
                    }

                    return true;
                });
            }
        }

        log.info("Job[handleExpiredTask]任务执行完毕, 执行用时: {}s", (System.currentTimeMillis() - currentTimeMillis) / 1000);
    }

    /** Each period is generated atomically under a plan lock. Failed plans remain due for the next run. */
    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Shanghai")
    public void handlePlanTask() {
        PointsTaskBasicsService taskBasicsService = ApplicationContextHolder.getBean(PointsTaskBasicsService.class);
        PointsTaskRelationService taskRelationService = ApplicationContextHolder.getBean(PointsTaskRelationService.class);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        QueryPlanTaskParam param = new QueryPlanTaskParam();
        param.setNextPlanDate(today);
        param.setStatus(BoolEnum.TRUE.getCode());
        param.setPageSize(100L);
        param.setExcludedIds(new ArrayList<>());
        while (true) {
            List<PointsTaskPlanEntity> plans = pointsTaskPlanDao.getBaseMapper().queryPlanTask(param);
            if (plans.isEmpty()) { break; }
            for (PointsTaskPlanEntity candidate : plans) {
                try {
                    UserUtils.setUserId(candidate.getUserId());
                    transactionTemplate.execute(action -> {
                        PointsTaskPlanEntity plan = pointsTaskPlanDao.lambdaQuery()
                                .eq(PointsTaskPlanEntity::getId, candidate.getId())
                                .eq(PointsTaskPlanEntity::getUserId, candidate.getUserId()).last("FOR UPDATE").one();
                        if (plan == null || !BoolEnum.TRUE.getCode().equals(plan.getStatus()) || plan.getNextPlanDate().isAfter(today)) { return false; }
                        LocalDate periodDate = plan.getPlanDate();
                        // A logically deleted task still keeps its unique source-period key.
                        TaskBasicsAddDto addDto = new TaskBasicsAddDto();
                        addDto.setTaskGroupId(WebCommonConstants.DATABASE_DEFAULT_INT_VALUE);
                        addDto.setTaskName(plan.getTaskName());
                        addDto.setTaskRemark(plan.getTaskRemark());
                        addDto.setDeadlineDate(periodDate.plusDays(plan.getDeadlineDays()));
                        Integer taskId = taskBasicsService.add(addDto);
                        pointsTaskBasicsDao.lambdaUpdate().eq(PointsTaskBasicsEntity::getId, taskId)
                                .set(PointsTaskBasicsEntity::getSourcePlanId, plan.getId())
                                .set(PointsTaskBasicsEntity::getSourcePlanDate, periodDate).update();
                        if (Boolean.TRUE.equals(plan.getBookkeepingEnabled())) {
                            PointsTaskBookkeepingEntity snapshot = new PointsTaskBookkeepingEntity();
                            snapshot.setUserId(plan.getUserId());
                            snapshot.setTaskId(taskId);
                            snapshot.setSourcePlanId(plan.getId());
                            snapshot.setSourcePlanDate(periodDate);
                            snapshot.setTaskNameSnapshot(plan.getTaskName());
                            snapshot.setTemplateSnapshot(plan.getBookkeepingTemplate());
                            snapshot.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                            snapshot.setUpdateTime(snapshot.getCreateTime());
                            taskBookkeepingDao.save(snapshot);
                        }
                        if (plan.getPunishPoints() > 0 || plan.getRewardPoints() > 0) {
                            PointsTaskRelationAddDto relation = new PointsTaskRelationAddDto();
                            relation.setTaskId(taskId);
                            relation.setRewardPoints(plan.getRewardPoints());
                            relation.setPunishPoints(plan.getPunishPoints());
                            taskRelationService.add(relation);
                        }
                        LocalDate nextPeriod = computePlanDate(plan);
                        pointsTaskPlanDao.lambdaUpdate().eq(PointsTaskPlanEntity::getId, plan.getId())
                                .set(PointsTaskPlanEntity::getPlanDate, nextPeriod)
                                .set(PointsTaskPlanEntity::getNextPlanDate, nextPeriod.minusDays(plan.getRemindDays())).update();
                        return true;
                    });
                } catch (Exception e) {
                    param.getExcludedIds().add(candidate.getId());
                    log.error("生成计划任务失败，计划 {} 保留到期状态供下次重试", candidate.getId(), e);
                } finally {
                    UserUtils.removeUserId();
                }
            }
        }
    }

    private void syncPoints(PointsTaskBasicsEntity taskBasicsEntity, Integer points, boolean isFinish) {
        if (points == null || points == 0) {
            return;
        }
        PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
        pointsRecordsAddDto.setTransactionType(PointsTransactionTypeEnum.DEDUCT.getCode());
        pointsRecordsAddDto.setPoints(isFinish ? points : -points);
        pointsRecordsAddDto.setSource((isFinish ? "完成任务" : "取消任务") + "[" + taskBasicsEntity.getId() + "]" + taskBasicsEntity.getTaskName());
        pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.POINTS_TASK_MANUAL.getCode());
        pointsRecordsAddDto.setUserId(taskBasicsEntity.getUserId());
        MQProducerHelper.send(PointsRecordsTopicEnum.TASK, pointsRecordsAddDto);
    }

    private LocalDate computePlanDate(PointsTaskPlanEntity entity) {
        LocalDate planDate = entity.getPlanDate();
        switch (entity.getPlanCycle()) {
            case DAY -> planDate = planDate.plusDays(1);
            case WEEK -> planDate = planDate.plusWeeks(1);
            case MONTH -> planDate = planDate.plusMonths(1);
            case YEAR -> planDate = planDate.plusYears(1);
            case CUSTOM -> planDate = planDate.plusDays(entity.getCycleDays());
        }
        return planDate;
    }
}
