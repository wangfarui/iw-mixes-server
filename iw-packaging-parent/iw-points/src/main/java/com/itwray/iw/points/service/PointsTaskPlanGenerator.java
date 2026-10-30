package com.itwray.iw.points.service;

import com.itwray.iw.points.dao.PointsTaskBasicsDao;
import com.itwray.iw.points.dao.PointsTaskBookkeepingDao;
import com.itwray.iw.points.model.dto.task.PointsTaskRelationAddDto;
import com.itwray.iw.points.model.dto.task.TaskBasicsAddDto;
import com.itwray.iw.points.model.entity.PointsTaskBasicsEntity;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanGenerateVo;
import com.itwray.iw.web.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Shared generation rules. Callers must hold the source plan row lock in this transaction. */
@Service
public class PointsTaskPlanGenerator {
    private final PointsTaskBasicsDao tasks;
    private final PointsTaskBasicsService taskService;
    private final PointsTaskRelationService relationService;
    private final PointsTaskBookkeepingDao bookkeeping;

    public PointsTaskPlanGenerator(PointsTaskBasicsDao tasks, PointsTaskBasicsService taskService,
                                   PointsTaskRelationService relationService, PointsTaskBookkeepingDao bookkeeping) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.relationService = relationService;
        this.bookkeeping = bookkeeping;
    }

    /** A null requestId denotes scheduled generation; a non-null id denotes manual generation. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public PointsTaskPlanGenerateVo generate(PointsTaskPlanEntity plan, LocalDate planDate, String requestId) {
        PointsTaskBasicsEntity previous = requestId == null
                ? tasks.getBaseMapper().queryScheduledGeneration(plan.getId(), planDate, plan.getUserId())
                : tasks.getBaseMapper().queryManualGeneration(plan.getId(), requestId, plan.getUserId());
        if (previous != null) {
            PointsTaskPlanGenerateVo result = result(previous.getId(), previous.getSourcePlanDate(), previous.getDeadlineDate());
            result.setTaskDeleted(Boolean.TRUE.equals(previous.getDeleted()));
            return result;
        }

        int deadlineDays = valueOrZero(plan.getDeadlineDays());
        int rewardPoints = valueOrZero(plan.getRewardPoints());
        int punishPoints = valueOrZero(plan.getPunishPoints());
        if (deadlineDays < 0 || rewardPoints < 0 || punishPoints < 0) {
            throw new BusinessException("截止天数和积分不能为负数，请检查任务计划");
        }
        if (Boolean.TRUE.equals(plan.getBookkeepingEnabled()) && plan.getBookkeepingTemplate() == null) {
            throw new BusinessException("任务计划缺少记账模板，请先编辑计划");
        }
        LocalDate deadlineDate = planDate.plusDays(deadlineDays);
        TaskBasicsAddDto dto = new TaskBasicsAddDto();
        dto.setTaskGroupId(0);
        dto.setTaskName(plan.getTaskName());
        dto.setTaskRemark(plan.getTaskRemark());
        dto.setDeadlineDate(deadlineDate);
        Integer taskId = taskService.add(dto);
        boolean updated = tasks.lambdaUpdate().eq(PointsTaskBasicsEntity::getId, taskId)
                .set(PointsTaskBasicsEntity::getSourcePlanId, plan.getId())
                .set(PointsTaskBasicsEntity::getSourcePlanDate, planDate)
                .set(PointsTaskBasicsEntity::getSourceRequestId, requestId).update();
        if (!updated) { throw new BusinessException("保存任务来源失败，本次生成未保存"); }

        if (Boolean.TRUE.equals(plan.getBookkeepingEnabled())) {
            PointsTaskBookkeepingEntity snapshot = new PointsTaskBookkeepingEntity();
            snapshot.setUserId(plan.getUserId());
            snapshot.setTaskId(taskId);
            snapshot.setSourcePlanId(plan.getId());
            snapshot.setSourcePlanDate(planDate);
            snapshot.setTaskNameSnapshot(plan.getTaskName());
            snapshot.setTemplateSnapshot(plan.getBookkeepingTemplate());
            snapshot.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            snapshot.setUpdateTime(snapshot.getCreateTime());
            if (!bookkeeping.save(snapshot)) { throw new BusinessException("保存任务记账模板失败，本次生成未保存"); }
        }
        if (rewardPoints > 0 || punishPoints > 0) {
            PointsTaskRelationAddDto relation = new PointsTaskRelationAddDto();
            relation.setTaskId(taskId);
            relation.setRewardPoints(rewardPoints);
            relation.setPunishPoints(punishPoints);
            relationService.add(relation);
        }
        return result(taskId, planDate, deadlineDate);
    }

    private int valueOrZero(Integer value) { return value == null ? 0 : value; }

    private PointsTaskPlanGenerateVo result(Integer taskId, LocalDate planDate, LocalDate deadlineDate) {
        PointsTaskPlanGenerateVo result = new PointsTaskPlanGenerateVo();
        result.setTaskId(taskId);
        result.setPlanDate(planDate);
        result.setDeadlineDate(deadlineDate);
        return result;
    }
}
