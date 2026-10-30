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
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanGenerateDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPageDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateDto;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanUpdateStatusDto;
import com.itwray.iw.points.model.entity.PointsTaskPlanEntity;
import com.itwray.iw.points.model.enums.TaskPlanCycleEnum;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanDetailVo;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanGenerateVo;
import com.itwray.iw.points.model.vo.plan.PointsTaskPlanPageVo;
import com.itwray.iw.points.service.PointsTaskPlanService;
import com.itwray.iw.points.service.PointsTaskPlanCalendar;
import com.itwray.iw.points.model.dto.plan.PointsTaskPlanPreviewDto;
import com.itwray.iw.points.service.PointsTaskPlanGenerator;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

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
    private PointsTaskPlanGenerator taskPlanGenerator;
    private PointsTaskPlanCalendar calendar = new PointsTaskPlanCalendar();

    @Autowired
    public void setCalendar(PointsTaskPlanCalendar calendar) { this.calendar = calendar; }

    @Autowired
    public void setTaskPlanGenerator(PointsTaskPlanGenerator generator) { this.taskPlanGenerator = generator; }

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

        PointsTaskPlanEntity pointsTaskPlanEntity = BeanUtil.copyProperties(dto, PointsTaskPlanEntity.class);
        applySchedule(calendar.prepare(dto, null), pointsTaskPlanEntity);
        pointsTaskPlanEntity.setStatus(1);
        applyBookkeeping(dto, pointsTaskPlanEntity);

        if (!getBaseDao().save(pointsTaskPlanEntity)) { throw new BusinessException("保存任务计划失败"); }
        generateDueOnSave(pointsTaskPlanEntity);

        return pointsTaskPlanEntity.getId();
    }

    @Override
    @Transactional
    public void update(PointsTaskPlanUpdateDto dto) {
        this.checkAddDto(dto);
        PointsTaskPlanEntity existing = lockPlan(dto.getId());

        PointsTaskPlanEntity entity = BeanUtil.copyProperties(dto, PointsTaskPlanEntity.class);
        applySchedule(calendar.prepare(dto, existing), entity);
        entity.setStatus(existing.getStatus());
        if (dto.getBookkeepingEnabled() != null) { applyBookkeeping(dto, entity); }
        else {
            entity.setBookkeepingEnabled(existing.getBookkeepingEnabled());
            entity.setBookkeepingTemplate(existing.getBookkeepingTemplate());
        }

        if (!getBaseDao().updateById(entity)) { throw new BusinessException("保存任务计划失败"); }
        if (!calendar.sameRule(entity, existing)) { generateDueOnSave(entity); }
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
        PageVo<PointsTaskPlanPageVo> result = getBaseDao().page(dto, queryWrapper, PointsTaskPlanPageVo.class);
        for (PointsTaskPlanPageVo vo : result.getRecords()) {
            PointsTaskPlanEntity plan = BeanUtil.copyProperties(vo, PointsTaskPlanEntity.class);
            vo.setCalendarType(plan.getCalendarType() == null ? "SOLAR" : plan.getCalendarType());
            vo.setPlanDateText(calendar.dateText(vo.getCalendarType(), vo.getPlanDate()));
            vo.setRuleText(calendar.ruleText(plan));
        }
        return result;
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
        vo.setCalendarType(entity.getCalendarType() == null ? "SOLAR" : entity.getCalendarType());
        vo.setAnchorDate(calendar.anchor(entity));
        vo.setPlanDateText(calendar.dateText(vo.getCalendarType(), entity.getPlanDate()));
        vo.setRuleText(calendar.ruleText(entity));
        if ("LUNAR".equals(vo.getCalendarType())) {
            var selected = calendar.selection(vo.getAnchorDate());
            vo.setLunarYear(selected.lunarYear());
            vo.setLunarMonth(selected.lunarMonth());
            vo.setLunarDay(selected.lunarDay());
            vo.setLeapMonth(selected.leapMonth());
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsTaskPlanGenerateVo generateNow(PointsTaskPlanGenerateDto dto) {
        PointsTaskPlanEntity plan = lockPlan(dto.getId());
        // Disabling a plan stops its schedule, but does not prevent a manual execution.
        return taskPlanGenerator.generate(plan, LocalDate.now(ZoneId.of("Asia/Shanghai")), dto.getRequestId());
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

    @Override
    public PointsTaskPlanCalendar.Preview preview(PointsTaskPlanPreviewDto dto) {
        PointsTaskPlanEntity existing = null;
        if (dto.getId() != null) {
            existing = getBaseDao().lambdaQuery().eq(PointsTaskPlanEntity::getId, dto.getId())
                    .eq(PointsTaskPlanEntity::getUserId, UserUtils.getUserId()).one();
            if (existing == null) { throw new BusinessException("任务计划不存在或无权操作"); }
        }
        return calendar.preview(calendar.prepare(dto, existing));
    }

    private void applySchedule(PointsTaskPlanEntity schedule, PointsTaskPlanEntity entity) {
        entity.setCalendarType(schedule.getCalendarType());
        entity.setAnchorDate(schedule.getAnchorDate());
        entity.setPlanDate(schedule.getPlanDate());
        entity.setNextPlanDate(schedule.getNextPlanDate());
        entity.setCycleDays(schedule.getCycleDays());
        entity.setRemindDays(schedule.getRemindDays());
        entity.setDeadlineDays(schedule.getDeadlineDays());
    }

    /** The inserted/updated plan is locked in the current transaction. */
    private void generateDueOnSave(PointsTaskPlanEntity plan) {
        if (!Integer.valueOf(1).equals(plan.getStatus()) || plan.getNextPlanDate().isAfter(calendar.today())) { return; }
        // save() fills userId through MyBatis metadata; keep the owner explicit for generation.
        plan.setUserId(UserUtils.getUserId());
        taskPlanGenerator.generate(plan, plan.getPlanDate(), null);
        LocalDate next = calendar.next(plan);
        var update = getBaseDao().lambdaUpdate().eq(PointsTaskPlanEntity::getId, plan.getId());
        if (next == null) {
            update.set(PointsTaskPlanEntity::getStatus, 0);
        } else {
            update.set(PointsTaskPlanEntity::getPlanDate, next)
                    .set(PointsTaskPlanEntity::getNextPlanDate, next.minusDays(plan.getRemindDays()));
        }
        if (!update.update()) { throw new BusinessException("推进计划日期失败，本次生成未保存"); }
    }

    /**
     * 数据校验
     */
    private void checkAddDto(PointsTaskPlanAddDto dto) {
        if (dto.getPlanCycle() == null) { throw new BusinessException("计划周期不能为空"); }
        if ((dto.getRemindDays() != null && dto.getRemindDays() < 0) || (dto.getDeadlineDays() != null && dto.getDeadlineDays() < 0)
                || (dto.getRewardPoints() != null && dto.getRewardPoints() < 0) || (dto.getPunishPoints() != null && dto.getPunishPoints() < 0)) {
            throw new BusinessException("提醒天数、截止天数和积分不能为负数");
        }
        if (dto.getPlanCycle().equals(TaskPlanCycleEnum.CUSTOM)) {
            if (dto.getCycleDays() == null || dto.getCycleDays() <= 0) {
                throw new BusinessException("计划天数不能为空");
            }
        }
    }
}
