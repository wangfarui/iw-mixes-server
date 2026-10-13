package com.itwray.iw.points.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingGateway;
import com.itwray.iw.points.bookkeeping.TaskBookkeepingTemplateDto;
import com.itwray.iw.web.utils.UserUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.points.dao.PointsTaskPlanDao;
import com.itwray.iw.points.mapper.PointsTaskPlanMapper;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanAddDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPageDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateStatusDto;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanDetailVo;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanPageVo;
import com.itwray.iw.points.service.PointsTaskPlanService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 任务计划表 服务实现类
 *
 * @author wray
 * @since 2025-05-07
 */
@Service
public class PointsTaskPlanServiceImpl extends WebServiceImpl<PointsTaskPlanDao, PointsTaskPlanMapper, PointsTaskPlanEntity,
        PointsTaskPlanAddDto, PointsTaskPlanUpdateDto, PointsTaskPlanDetailVo, Integer>  implements PointsTaskPlanService {

    private TaskBookkeepingGateway taskBookkeepingGateway;

    @Autowired
    public void setTaskBookkeepingGateway(TaskBookkeepingGateway gateway) { this.taskBookkeepingGateway = gateway; }

    @Autowired
    public PointsTaskPlanServiceImpl(PointsTaskPlanDao baseDao) {
        super(baseDao);
    }

    @Override
    @Transactional
    public Integer add(PointsTaskPlanAddDto dto) {
        this.checkAddDto(dto);

        LocalDate nextPlanDate = this.computeNextPlanDate(dto);
        PointsTaskPlanEntity pointsTaskPlanEntity = BeanUtil.copyProperties(dto, PointsTaskPlanEntity.class);
        pointsTaskPlanEntity.setNextPlanDate(nextPlanDate);
        applyBookkeeping(dto, pointsTaskPlanEntity);

        getBaseDao().save(pointsTaskPlanEntity);

        return pointsTaskPlanEntity.getId();
    }

    @Override
    @Transactional
    public void update(PointsTaskPlanUpdateDto dto) {
        this.checkAddDto(dto);
        PointsTaskPlanEntity existing = lockPlan(dto.getId());

        LocalDate nextPlanDate = this.computeNextPlanDate(dto);
        PointsTaskPlanEntity entity = BeanUtil.copyProperties(dto, PointsTaskPlanEntity.class);
        entity.setNextPlanDate(nextPlanDate);
        if (dto.getBookkeepingEnabled() != null) { applyBookkeeping(dto, entity); }
        else {
            entity.setBookkeepingEnabled(existing.getBookkeepingEnabled());
            entity.setBookkeepingTemplate(existing.getBookkeepingTemplate());
        }

        getBaseDao().updateById(entity);
        if (Boolean.FALSE.equals(dto.getBookkeepingEnabled())) {
            getBaseDao().lambdaUpdate().eq(PointsTaskPlanEntity::getId, dto.getId())
                    .set(PointsTaskPlanEntity::getBookkeepingTemplate, null).update();
        }
    }

    @Override
    public PageVo<PointsTaskPlanPageVo> page(PointsTaskPlanPageDto dto) {
        LambdaQueryWrapper<PointsTaskPlanEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getTaskName()), PointsTaskPlanEntity::getTaskName, dto.getTaskName())
                .eq(dto.getStatus() != null, PointsTaskPlanEntity::getStatus, dto.getStatus())
                .orderByAsc(PointsTaskPlanEntity::getNextPlanDate)
                .orderByDesc(PointsTaskPlanEntity::getId);
        return getBaseDao().page(dto, queryWrapper, PointsTaskPlanPageVo.class);
    }

    @Override
    @Transactional
    public void updatePlanStatus(PointsTaskPlanUpdateStatusDto dto) {
        lockPlan(dto.getId());
        getBaseDao().lambdaUpdate()
                .eq(PointsTaskPlanEntity::getId, dto.getId())
                .set(PointsTaskPlanEntity::getStatus, dto.getStatus())
                .update();
    }

    @Override
    public PointsTaskPlanDetailVo detail(Integer id) {
        PointsTaskPlanEntity entity = getBaseDao().queryById(id);
        PointsTaskPlanDetailVo vo = BeanUtil.copyProperties(entity, PointsTaskPlanDetailVo.class, "bookkeepingTemplate");
        if (entity.getBookkeepingTemplate() != null) {
            vo.setBookkeepingTemplate(JSONUtil.toBean(entity.getBookkeepingTemplate(), TaskBookkeepingTemplateDto.class));
        }
        return vo;
    }

    private void applyBookkeeping(PointsTaskPlanAddDto dto, PointsTaskPlanEntity entity) {
        boolean enabled = Boolean.TRUE.equals(dto.getBookkeepingEnabled());
        entity.setBookkeepingEnabled(enabled);
        entity.setBookkeepingTemplate(enabled ? JSONUtil.toJsonStr(taskBookkeepingGateway.validateAndBind(dto.getBookkeepingTemplate())) : null);
    }

    private PointsTaskPlanEntity lockPlan(Integer id) {
        PointsTaskPlanEntity entity = getBaseDao().lambdaQuery().eq(PointsTaskPlanEntity::getId, id)
                .eq(PointsTaskPlanEntity::getUserId, UserUtils.getUserId()).last("FOR UPDATE").one();
        if (entity == null) { throw new BusinessException("任务计划不存在或无权操作"); }
        return entity;
    }

    private LocalDate computeNextPlanDate(PointsTaskPlanAddDto dto) {
        LocalDate nextPlanDate = dto.getPlanDate();
        if (dto.getRemindDays() != null) {
            nextPlanDate = nextPlanDate.minusDays(dto.getRemindDays());
        }
        // 如果"下一次计划生成日期"在当天之后, 则日期生效
        if (nextPlanDate.isAfter(LocalDate.now())) {
            return nextPlanDate;
        }
        // 否则"计划日期"根据"计划周期"自增
        LocalDate planDate = dto.getPlanDate();
        switch (dto.getPlanCycle()) {
            case DAY -> planDate = planDate.plusDays(1);
            case WEEK -> planDate = planDate.plusWeeks(1);
            case MONTH -> planDate = planDate.plusMonths(1);
            case YEAR -> planDate = planDate.plusYears(1);
            case CUSTOM -> planDate = planDate.plusDays(dto.getCycleDays());
        }
        dto.setPlanDate(planDate);
        return computeNextPlanDate(dto);
    }

    /**
     * 数据校验
     */
    private void checkAddDto(PointsTaskPlanAddDto dto) {
        if (dto.getPlanCycle() == null || dto.getPlanDate() == null) { throw new BusinessException("计划周期和计划日期不能为空"); }
        if ((dto.getRemindDays() != null && dto.getRemindDays() < 0) || (dto.getDeadlineDays() != null && dto.getDeadlineDays() < 0)
                || (dto.getRewardPoints() != null && dto.getRewardPoints() < 0) || (dto.getPunishPoints() != null && dto.getPunishPoints() < 0)) {
            throw new BusinessException("提醒天数、截止天数和积分不能为负数");
        }
        if (dto.getPlanCycle().equals(TaskPlanCycleEnum.CUSTOM)) {
            if (dto.getCycleDays() == null || dto.getCycleDays() <= 0) {
                throw new BusinessException("计划天数不能为空");
            }
        }
        if (dto.getPlanDate().isBefore(LocalDate.now())) {
            throw new BusinessException("计划日期不能是历史日期");
        }
    }
}
