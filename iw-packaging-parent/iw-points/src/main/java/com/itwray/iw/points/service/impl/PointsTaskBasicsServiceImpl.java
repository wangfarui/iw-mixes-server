package com.itwray.iw.points.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.points.bookkeeping.*;
import com.itwray.iw.points.dao.PointsTaskBookkeepingDao;
import com.itwray.iw.points.model.entity.PointsTaskBookkeepingEntity;
import com.itwray.iw.points.model.vo.task.TaskStatusResultVo;
import com.itwray.iw.points.service.PointsRecordsService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.points.dao.PointsTaskBasicsDao;
import com.itwray.iw.points.dao.PointsTaskGroupDao;
import com.itwray.iw.points.dao.PointsTaskRelationDao;
import com.itwray.iw.points.mapper.PointsTaskBasicsMapper;
import com.itwray.iw.points.model.dto.PointsRecordsAddDto;
import com.itwray.iw.points.model.dto.task.*;
import com.itwray.iw.points.model.entity.PointsTaskBasicsEntity;
import com.itwray.iw.points.model.entity.PointsTaskRelationEntity;
import com.itwray.iw.points.model.enums.PointsSourceTypeEnum;
import com.itwray.iw.points.model.enums.PointsTransactionTypeEnum;
import com.itwray.iw.points.model.enums.TaskStatusEnum;
import com.itwray.iw.points.model.vo.task.TaskBasicsDetailVo;
import com.itwray.iw.points.model.vo.task.TaskBasicsListVo;
import com.itwray.iw.points.model.vo.task.TaskBasicsPageVo;
import com.itwray.iw.points.service.PointsTaskBasicsService;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.dao.BaseBusinessFileDao;
import com.itwray.iw.web.model.enums.BusinessFileTypeEnum;
import com.itwray.iw.web.model.vo.FileVo;
import com.itwray.iw.web.model.vo.PageVo;
import com.itwray.iw.web.service.impl.WebServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 任务基础表 服务实现类
 *
 * @author wray
 * @since 2025-03-19
 */
@Service
public class PointsTaskBasicsServiceImpl extends WebServiceImpl<PointsTaskBasicsDao, PointsTaskBasicsMapper, PointsTaskBasicsEntity,
        TaskBasicsAddDto, TaskBasicsUpdateDto, TaskBasicsDetailVo, Integer> implements PointsTaskBasicsService {

    private final PointsTaskGroupDao pointsTaskGroupDao;

    private final PointsTaskRelationDao pointsTaskRelationDao;

    private BaseBusinessFileDao baseBusinessFileDao;
    private PointsTaskBookkeepingDao taskBookkeepingDao;
    private TaskBookkeepingGateway bookkeepingGateway;
    private PointsRecordsService pointsRecordsService;

    @Autowired
    public void setTaskBookkeepingDao(PointsTaskBookkeepingDao dao) { this.taskBookkeepingDao = dao; }
    @Autowired
    public void setBookkeepingGateway(TaskBookkeepingGateway gateway) { this.bookkeepingGateway = gateway; }
    @Autowired
    public void setPointsRecordsService(PointsRecordsService service) { this.pointsRecordsService = service; }

    @Autowired
    public PointsTaskBasicsServiceImpl(PointsTaskBasicsDao baseDao, PointsTaskGroupDao pointsTaskGroupDao, PointsTaskRelationDao pointsTaskRelationDao) {
        super(baseDao);
        this.pointsTaskGroupDao = pointsTaskGroupDao;
        this.pointsTaskRelationDao = pointsTaskRelationDao;
    }

    @Autowired
    public void setBaseBusinessFileDao(BaseBusinessFileDao baseBusinessFileDao) {
        this.baseBusinessFileDao = baseBusinessFileDao;
    }

    @Override
    @Transactional
    public Integer add(TaskBasicsAddDto dto) {
        dto.setSort(getBaseDao().queryMaxSortByGroupId(dto.getTaskGroupId()));
        return super.add(dto);
    }

    @Override
    @Transactional
    public void update(TaskBasicsUpdateDto dto) {
        lockTask(dto.getId());
        super.update(dto);
    }

    @Override
    public List<TaskBasicsListVo> queryList(TaskBasicsListDto dto) {
        List<PointsTaskBasicsEntity> entityList = getBaseDao().getBaseMapper().queryList(dto);
        return this.buildListVo(entityList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskStatusResultVo updateTaskStatus(TaskBasicsUpdateStatusDto dto) {
        PointsTaskBasicsEntity task = lockTask(dto.getId());
        PointsTaskBookkeepingEntity link = taskBookkeepingDao.lockByTaskId(task.getId());
        boolean pending = needsBookkeeping(link, queryLinkedRecord(link));
        String action = link != null && !pending ? "KEPT" : "NONE";
        if (task.getTaskStatus().equals(dto.getTaskStatus())) {
            return statusResult(task, link, action);
        }
        if (TaskStatusEnum.DONE.equals(dto.getTaskStatus()) && !TaskStatusEnum.WAIT.equals(task.getTaskStatus())) {
            throw new BusinessException("只有待办任务可以完成");
        }
        LocalDateTime completedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int reward = 0;
        if (TaskStatusEnum.DONE.equals(dto.getTaskStatus())) {
            if (pending) {
                TaskBookkeepingTemplateDto snapshot = JSONUtil.toBean(link.getTemplateSnapshot(), TaskBookkeepingTemplateDto.class);
                TaskBookkeepingRecordVo record = bookkeepingGateway.create(snapshot, completedAt);
                link.setBookkeepingRecordId(record.getId());
                link.setRecordedAt(completedAt);
                link.setUpdateTime(completedAt);
                if (!taskBookkeepingDao.updateById(link)) {
                    throw new BusinessException("保存任务记账关联失败，本次操作未保存");
                }
                action = "CREATED";
            }
            PointsTaskRelationEntity relation = pointsTaskRelationDao.getByTaskId(task.getId());
            if (relation != null && (task.getDeadlineDate() == null || !completedAt.toLocalDate().isAfter(task.getDeadlineDate()))) {
                reward = relation.getRewardPoints() == null ? 0 : relation.getRewardPoints();
                syncPoints(task, reward, true);
            }
        } else if (TaskStatusEnum.DONE.equals(task.getTaskStatus())) {
            syncPoints(task, task.getCompletionRewardPoints(), false);
        }
        getBaseDao().lambdaUpdate().eq(PointsTaskBasicsEntity::getId, task.getId())
                .set(PointsTaskBasicsEntity::getTaskStatus, dto.getTaskStatus())
                .set(PointsTaskBasicsEntity::getDoneTime, TaskStatusEnum.DONE.equals(dto.getTaskStatus()) ? completedAt : null)
                .set(PointsTaskBasicsEntity::getCompletionRewardPoints, reward)
                .set(PointsTaskBasicsEntity::getUpdateTime, completedAt).update();
        task.setTaskStatus(dto.getTaskStatus());
        return statusResult(task, link, action);
    }

    private PointsTaskBasicsEntity lockTask(Integer id) {
        PointsTaskBasicsEntity task = getBaseDao().lambdaQuery().eq(PointsTaskBasicsEntity::getId, id)
                .eq(PointsTaskBasicsEntity::getUserId, UserUtils.getUserId()).last("FOR UPDATE").one();
        if (task == null) { throw new BusinessException("任务不存在或无权操作"); }
        return task;
    }

    private TaskStatusResultVo statusResult(PointsTaskBasicsEntity task, PointsTaskBookkeepingEntity link, String action) {
        TaskStatusResultVo result = new TaskStatusResultVo();
        result.setId(task.getId());
        result.setTaskStatus(task.getTaskStatus().getCode());
        result.setBookkeepingAction(action);
        if (link != null && link.getBookkeepingRecordId() != null) {
            TaskBookkeepingRecordVo record = queryLinkedRecord(link);
            if (needsBookkeeping(link, record)) { return result; }
            result.setBookkeepingRecordId(link.getBookkeepingRecordId());
            if (record != null && "AVAILABLE".equals(record.getAvailability())) {
                result.setRecordedAmount(record.getAmount());
                result.setRecordedDate(record.getRecordDate());
            }
        }
        return result;
    }

    private TaskBookkeepingRecordVo queryLinkedRecord(PointsTaskBookkeepingEntity link) {
        if (link == null || link.getBookkeepingRecordId() == null) { return null; }
        return bookkeepingGateway.queryRecords(Collections.singletonList(link.getBookkeepingRecordId()))
                .get(link.getBookkeepingRecordId());
    }

    private boolean needsBookkeeping(PointsTaskBookkeepingEntity link, TaskBookkeepingRecordVo record) {
        return link != null && (link.getBookkeepingRecordId() == null
                || record != null && "DELETED".equals(record.getAvailability()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBookkeepingTemplate(TaskBasicsUpdateBookkeepingTemplateDto dto) {
        PointsTaskBasicsEntity task = lockTask(dto.getId());
        PointsTaskBookkeepingEntity link = taskBookkeepingDao.lockByTaskId(task.getId());
        if (link == null) { throw new BusinessException("该任务没有记账模板"); }
        if (!needsBookkeeping(link, queryLinkedRecord(link))) { throw new BusinessException("该任务已有关联账单，不能修改记账模板"); }
        if (!TaskStatusEnum.WAIT.equals(task.getTaskStatus())) { throw new BusinessException("仅待办任务可以修改本次记账模板"); }
        link.setTemplateSnapshot(JSONUtil.toJsonStr(bookkeepingGateway.validateAndBind(dto.getBookkeepingTemplate())));
        link.setUpdateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        taskBookkeepingDao.updateById(link);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        TaskBasicsUpdateStatusDto status = new TaskBasicsUpdateStatusDto();
        status.setId(id);
        status.setTaskStatus(TaskStatusEnum.DELETED);
        updateTaskStatus(status);
    }

    private void syncPoints(PointsTaskBasicsEntity taskBasicsEntity, Integer points, boolean isFinish) {
        if (points == null || points == 0) {
            return;
        }
        PointsRecordsAddDto pointsRecordsAddDto = new PointsRecordsAddDto();
        pointsRecordsAddDto.setTransactionType(isFinish ? PointsTransactionTypeEnum.INCREASE.getCode() : PointsTransactionTypeEnum.DEDUCT.getCode());
        pointsRecordsAddDto.setPoints(isFinish ? points : -points);
        pointsRecordsAddDto.setSource((isFinish ? "完成任务" : "取消任务") + "[" + taskBasicsEntity.getId() + "]" + taskBasicsEntity.getTaskName());
        pointsRecordsAddDto.setSourceType(PointsSourceTypeEnum.POINTS_TASK_MANUAL.getCode());
        pointsRecordsAddDto.setUserId(taskBasicsEntity.getUserId());
        pointsRecordsService.add(pointsRecordsAddDto);
    }

    @Override
    public List<TaskBasicsListVo> doneList(Integer taskGroupId, Integer currentPage) {
        List<PointsTaskBasicsEntity> entityList = getBaseDao().lambdaQuery()
                .eq(PointsTaskBasicsEntity::getTaskStatus, TaskStatusEnum.DONE)
                .eq(taskGroupId != null, PointsTaskBasicsEntity::getTaskGroupId, taskGroupId)
                .orderByDesc(PointsTaskBasicsEntity::getUpdateTime)
                // 默认每次只查10条数据
                .last(WebCommonConstants.standardPageLimit(currentPage, 10))
                .list();

        return this.buildListVo(entityList);
    }

    @Override
    @Transactional
    public List<TaskBasicsListVo> deletedList(Boolean more) {
        List<PointsTaskBasicsEntity> entityList = getBaseDao().lambdaQuery()
                .eq(PointsTaskBasicsEntity::getTaskStatus, TaskStatusEnum.DELETED)
                .orderByDesc(PointsTaskBasicsEntity::getUpdateTime)
                // 默认只查最近20条数据
                .last(!Boolean.TRUE.equals(more), WebCommonConstants.standardLimit(20))
                .list();

        return this.buildListVo(entityList);
    }

    @Override
    @Transactional
    public void clearDeletedList() {
        getBaseDao().lambdaUpdate()
                .eq(PointsTaskBasicsEntity::getTaskStatus, TaskStatusEnum.DELETED)
                .remove();
    }

    @Override
    @Transactional
    public void addTaskFile(TaskBasicsAddFileDto addFileDto) {
        getBaseDao().queryById(addFileDto.getTaskId());
        baseBusinessFileDao.addBusinessFile(addFileDto.getTaskId(), BusinessFileTypeEnum.POINTS_TASK_BASICS, Collections.singletonList(addFileDto));
    }

    @Override
    public void deleteTaskFile(TaskBasicsDeleteFileDto deleteFileDto) {
        getBaseDao().queryById(deleteFileDto.getTaskId());
        baseBusinessFileDao.removeBusinessFile(deleteFileDto.getTaskId(), BusinessFileTypeEnum.POINTS_TASK_BASICS, deleteFileDto.getFileUrl());
    }

    @Override
    public PageVo<TaskBasicsPageVo> page(TaskBasicsPageDto dto) {
        LambdaQueryWrapper<PointsTaskBasicsEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(dto.getTaskName()), PointsTaskBasicsEntity::getTaskName, dto.getTaskName())
                .eq(Objects.nonNull(dto.getTaskStatus()), PointsTaskBasicsEntity::getTaskStatus, dto.getTaskStatus())
                .ge(Objects.nonNull(dto.getStartDeadlineDate()), PointsTaskBasicsEntity::getDeadlineDate, dto.getStartDeadlineDate())
                .le(Objects.nonNull(dto.getEndDeadlineDate()), PointsTaskBasicsEntity::getDeadlineDate, dto.getEndDeadlineDate())
                .ge(Objects.nonNull(dto.getStartDoneTime()), PointsTaskBasicsEntity::getDoneTime, DateUtils.startTimeOfDay(dto.getStartDoneTime()))
                .le(Objects.nonNull(dto.getEndDoneTime()), PointsTaskBasicsEntity::getDoneTime, DateUtils.endTimeOfDay(dto.getEndDoneTime()))
                .orderByDesc(PointsTaskBasicsEntity::getId);
        PageVo<PointsTaskBasicsEntity> pageVo = getBaseDao().page(dto, queryWrapper);
        if (CollectionUtil.isEmpty(pageVo.getRecords())) {
            return PageVo.of(pageVo);
        }
        List<TaskBasicsListVo> listVos = this.buildListVo(pageVo.getRecords());
        return PageVo.of(pageVo, BeanUtil.copyToList(listVos, TaskBasicsPageVo.class));
    }

    @Override
    public TaskBasicsDetailVo detail(Integer id) {
        TaskBasicsDetailVo vo = super.detail(id);
        PointsTaskRelationEntity taskRelationEntity = pointsTaskRelationDao.getByTaskId(vo.getId());
        if (taskRelationEntity != null) {
            vo.setRewardPoints(taskRelationEntity.getRewardPoints());
            vo.setPunishPoints(taskRelationEntity.getPunishPoints());
        }
        List<FileVo> fileVoList = baseBusinessFileDao.getBusinessFile(vo.getId(), BusinessFileTypeEnum.POINTS_TASK_BASICS);
        vo.setFileList(fileVoList);
        PointsTaskBookkeepingEntity link = taskBookkeepingDao.byTaskId(id);
        TaskBookkeepingRecordVo record = queryLinkedRecord(link);
        boolean pending = needsBookkeeping(link, record);
        vo.setBookkeepingStatus(link == null ? "NONE" : pending ? "PENDING" : "RECORDED");
        if (link != null) {
            TaskBookkeepingTemplateDto template = JSONUtil.toBean(link.getTemplateSnapshot(), TaskBookkeepingTemplateDto.class);
            vo.setBookkeepingTemplate(template);
            vo.setBookkeepingRecordCategory(template.getRecordCategory());
            if (pending) {
                vo.setBookkeepingAmount(template.getAmount());
                vo.setBookkeepingRecordAvailability(record == null ? "NOT_CREATED" : "DELETED");
            } else {
                vo.setBookkeepingRecordId(link.getBookkeepingRecordId());
                vo.setBookkeepingRecordAvailability(record == null ? "INACCESSIBLE" : record.getAvailability());
                if (record != null && "AVAILABLE".equals(record.getAvailability())) { vo.setBookkeepingAmount(record.getAmount()); }
            }
        }
        return vo;
    }

    private List<TaskBasicsListVo> buildListVo(List<PointsTaskBasicsEntity> entityList) {
        if (CollectionUtil.isEmpty(entityList)) {
            return Collections.emptyList();
        }
        List<Integer> taskGroupIdList = entityList.stream().map(PointsTaskBasicsEntity::getTaskGroupId).distinct().toList();
        Map<Integer, String> groupNameMap = pointsTaskGroupDao.queryTaskGroupNameMap(taskGroupIdList);
        Map<Integer, PointsTaskBookkeepingEntity> links = taskBookkeepingDao.lambdaQuery()
                .in(PointsTaskBookkeepingEntity::getTaskId, entityList.stream().map(PointsTaskBasicsEntity::getId).toList()).list()
                .stream().collect(Collectors.toMap(PointsTaskBookkeepingEntity::getTaskId, Function.identity()));
        List<Integer> recordIds = links.values().stream().map(PointsTaskBookkeepingEntity::getBookkeepingRecordId).filter(Objects::nonNull).toList();
        Map<Integer, TaskBookkeepingRecordVo> records = recordIds.isEmpty() ? Collections.emptyMap() : bookkeepingGateway.queryRecords(recordIds);
        return entityList.stream()
                .map(t -> {
                    TaskBasicsListVo vo = BeanUtil.copyProperties(t, TaskBasicsListVo.class);
                    vo.setTaskGroupName(groupNameMap.get(vo.getTaskGroupId()));
                    PointsTaskBookkeepingEntity link = links.get(t.getId());
                    TaskBookkeepingRecordVo record = link == null ? null : records.get(link.getBookkeepingRecordId());
                    boolean pending = needsBookkeeping(link, record);
                    vo.setBookkeepingStatus(link == null ? "NONE" : pending ? "PENDING" : "RECORDED");
                    if (link != null) {
                        TaskBookkeepingTemplateDto template = JSONUtil.toBean(link.getTemplateSnapshot(), TaskBookkeepingTemplateDto.class);
                        vo.setBookkeepingRecordCategory(template.getRecordCategory());
                        vo.setBookkeepingRecordSource(template.getRecordSource());
                        if (pending) {
                            vo.setBookkeepingAmount(template.getAmount());
                            vo.setBookkeepingRecordAvailability(record == null ? "NOT_CREATED" : "DELETED");
                        } else {
                            vo.setBookkeepingRecordId(link.getBookkeepingRecordId());
                            vo.setBookkeepingRecordAvailability(record == null ? "INACCESSIBLE" : record.getAvailability());
                            if (record != null && "AVAILABLE".equals(record.getAvailability())) { vo.setBookkeepingAmount(record.getAmount()); }
                        }
                    }
                    return vo;
                })
                .toList();
    }
}
