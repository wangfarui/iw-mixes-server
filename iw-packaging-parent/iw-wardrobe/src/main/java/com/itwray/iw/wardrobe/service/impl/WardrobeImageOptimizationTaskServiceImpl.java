package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.util.IdUtil;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationAttemptDao;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageOptimizationTaskStatus;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;
import com.itwray.iw.wardrobe.service.WardrobeImageOptimizationTaskService;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class WardrobeImageOptimizationTaskServiceImpl implements WardrobeImageOptimizationTaskService {

    private final WardrobeItemDao itemDao;
    private final WardrobeImageOptimizationTaskDao taskDao;
    private final WardrobeImageOptimizationAttemptDao attemptDao;
    private final WardrobeImageOptimizationPromptFactory promptFactory;
    private final WardrobeItemAccessService accessService;

    public WardrobeImageOptimizationTaskServiceImpl(WardrobeItemDao itemDao,
                                                     WardrobeImageOptimizationTaskDao taskDao,
                                                     WardrobeImageOptimizationAttemptDao attemptDao,
                                                     WardrobeImageOptimizationPromptFactory promptFactory,
                                                     WardrobeItemAccessService accessService) {
        this.itemDao = itemDao;
        this.taskDao = taskDao;
        this.attemptDao = attemptDao;
        this.promptFactory = promptFactory;
        this.accessService = accessService;
    }

    @Override
    @Transactional
    public WardrobeItemImageOptimizeTaskVo start(WardrobeItemImageOptimizeDto dto) {
        if (dto == null || dto.getItemId() == null) {
            throw new BusinessException("衣物ID不能为空");
        }
        WardrobeItemEntity item = itemDao.queryByIdInOwnerIds(dto.getItemId(), accessService.resolveFamilyOwnerIds());
        accessService.requireManage(item);
        if (StringUtils.isBlank(item.getItemImage())) {
            throw new BusinessException("请先上传衣物图片");
        }
        Integer userId = item.getUserId();
        Integer operatorUserId = UserUtils.getUserId();
        WardrobeImageOptimizationPromptFactory.Input input = promptFactory.create(item, dto.getPrompt());
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(item.getId(), userId);
        if (active != null) {
            if (StringUtils.equals(active.getFingerprint(), input.fingerprint())) {
                return this.toVo(active, this.currentAttempt(active));
            }
            throw this.activeTaskConflict(active);
        }

        WardrobeImageOptimizationTaskEntity existing = taskDao.findByFingerprint(item.getId(), userId,
                input.fingerprint());
        if (existing != null) {
            if (WardrobeImageOptimizationTaskStatus.SUCCEEDED.getCode().equals(existing.getStatus())
                    && existing.getResultDeletedTime() != null) {
                return this.enqueueNextAttempt(existing);
            }
            return this.toVo(existing, this.currentAttempt(existing));
        }

        WardrobeImageOptimizationTaskEntity task = new WardrobeImageOptimizationTaskEntity();
        task.setTaskId(IdUtil.fastSimpleUUID());
        task.setUserId(userId);
        task.setItemId(item.getId());
        task.setRequesterUserId(operatorUserId);
        task.setFingerprint(input.fingerprint());
        task.setSourceImageUrl(input.sourceImageUrl());
        task.setUserPrompt(input.userPrompt());
        task.setNormalizedPrompt(input.normalizedPrompt());
        task.setRuleVersion(WardrobeImageOptimizationPromptFactory.RULE_VERSION);
        task.setInputSnapshot(input.snapshotJson());
        task.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        task.setCurrentAttemptNo(1);
        task.setResultImageUrl("");
        task.setErrorCode("");
        task.setErrorMessage("");
        try {
            taskDao.save(task);
        } catch (DuplicateKeyException e) {
            WardrobeImageOptimizationTaskEntity concurrent = taskDao.findActiveByItem(item.getId(), userId);
            if (concurrent != null && StringUtils.equals(concurrent.getFingerprint(), input.fingerprint())) {
                return this.toVo(concurrent, this.currentAttempt(concurrent));
            }
            if (concurrent != null) {
                throw this.activeTaskConflict(concurrent);
            }
            throw new BusinessException("该衣物已有图片优化任务正在处理中");
        }

        WardrobeImageOptimizationAttemptEntity attempt = new WardrobeImageOptimizationAttemptEntity();
        attempt.setTaskId(task.getTaskId());
        attempt.setUserId(userId);
        attempt.setOperatorUserId(operatorUserId);
        attempt.setAttemptNo(1);
        attempt.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        attemptDao.save(attempt);
        return this.toVo(task, attempt);
    }

    @Override
    @Transactional
    public WardrobeItemImageOptimizeTaskVo retry(String taskId) {
        WardrobeImageOptimizationTaskEntity task = this.requireTask(taskId);
        if (!WardrobeImageOptimizationTaskStatus.FAILED.getCode().equals(task.getStatus())) {
            throw new BusinessException("只有失败的图片优化任务可以重试");
        }
        WardrobeItemEntity item = itemDao.queryByIdInOwnerIds(task.getItemId(), accessService.resolveFamilyOwnerIds());
        accessService.requireManage(item);
        if (!StringUtils.equals(StringUtils.trimToEmpty(item.getItemImage()), task.getSourceImageUrl())) {
            throw new BusinessException("衣物源图已变化，不能重试原任务");
        }
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(task.getItemId(), task.getUserId());
        if (active != null) {
            throw this.activeTaskConflict(active);
        }
        return this.enqueueNextAttempt(task);
    }

    @Override
    public WardrobeItemImageOptimizeTaskVo get(String taskId) {
        WardrobeImageOptimizationTaskEntity task = this.requireTask(taskId);
        return this.toVo(task, this.currentAttempt(task));
    }

    @Override
    public WardrobeItemImageOptimizeTaskVo getCurrent(Integer itemId) {
        if (itemId == null) {
            throw new BusinessException("衣物不存在或已删除");
        }
        WardrobeItemEntity item = itemDao.queryByIdInOwnerIds(itemId, accessService.resolveFamilyOwnerIds());
        accessService.requireManage(item);
        WardrobeImageOptimizationTaskEntity task = taskDao.findLatestByItem(itemId, item.getUserId());
        return task == null ? null : this.toVo(task, this.currentAttempt(task));
    }

    @Override
    public void assertSourceImageChangeAllowed(Integer itemId, Integer ownerUserId, String nextSourceImageUrl) {
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(itemId, ownerUserId);
        if (active != null && !StringUtils.equals(active.getSourceImageUrl(),
                StringUtils.trimToEmpty(nextSourceImageUrl))) {
            throw new BusinessException("图片优化处理中，暂不能修改衣物源图");
        }
    }

    @Override
    public void assertOwnerChangeAllowed(Integer itemId, Integer ownerUserId) {
        if (taskDao.findActiveByItem(itemId, ownerUserId) != null) {
            throw new BusinessException("图片优化处理中，暂不能变更所属人");
        }
    }

    @Override
    @Transactional
    public void transferOwnership(Integer itemId, Integer previousOwnerUserId, Integer nextOwnerUserId) {
        if (Objects.equals(previousOwnerUserId, nextOwnerUserId)) {
            return;
        }
        List<String> taskIds = taskDao.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, previousOwnerUserId)
                .list()
                .stream()
                .map(WardrobeImageOptimizationTaskEntity::getTaskId)
                .toList();
        taskDao.lambdaUpdate()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, previousOwnerUserId)
                .set(WardrobeImageOptimizationTaskEntity::getUserId, nextOwnerUserId)
                .update();
        if (!taskIds.isEmpty()) {
            attemptDao.lambdaUpdate()
                    .in(WardrobeImageOptimizationAttemptEntity::getTaskId, taskIds)
                    .eq(WardrobeImageOptimizationAttemptEntity::getUserId, previousOwnerUserId)
                    .set(WardrobeImageOptimizationAttemptEntity::getUserId, nextOwnerUserId)
                    .update();
        }
    }

    @Override
    @Transactional
    public void cancelForItemDeletion(Integer itemId, Integer ownerUserId) {
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(itemId, ownerUserId);
        if (active == null) {
            return;
        }
        active = taskDao.findByTaskIdForUpdate(active.getTaskId(), ownerUserId);
        if (active == null || !WardrobeImageOptimizationTaskStatus.QUEUED.getCode().equals(active.getStatus())
                && !WardrobeImageOptimizationTaskStatus.RUNNING.getCode().equals(active.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        active.setStatus(WardrobeImageOptimizationTaskStatus.CANCELLED.getCode());
        active.setErrorCode("");
        active.setErrorMessage("衣物已删除");
        active.setCompleteTime(now);
        taskDao.updateByTaskIdAndOwner(active, ownerUserId);
        WardrobeImageOptimizationAttemptEntity attempt = this.currentAttempt(active);
        if (attempt != null) {
            attempt.setStatus(WardrobeImageOptimizationTaskStatus.CANCELLED.getCode());
            attempt.setErrorCode("");
            attempt.setErrorMessage("衣物已删除");
            attempt.setCompleteTime(now);
            attempt.setClaimToken("");
            attemptDao.updateByTaskAndOwner(attempt, ownerUserId);
        }
    }

    @Override
    @Transactional
    public void markResultDeleted(Integer itemId, Integer ownerUserId, String resultImageUrl) {
        WardrobeImageOptimizationTaskEntity task = taskDao.findSucceededByResult(
                itemId, ownerUserId, resultImageUrl);
        if (task == null) {
            return;
        }
        task.setResultDeletedTime(LocalDateTime.now());
        taskDao.updateByTaskIdAndOwner(task, ownerUserId);
    }

    private WardrobeItemImageOptimizeTaskVo enqueueNextAttempt(WardrobeImageOptimizationTaskEntity task) {
        int attemptNo = task.getCurrentAttemptNo() == null ? 1 : task.getCurrentAttemptNo() + 1;
        task.setCurrentAttemptNo(attemptNo);
        task.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setResultImageUrl("");
        task.setResultDeletedTime(null);
        task.setCompleteTime(null);
        taskDao.updateByTaskIdAndOwner(task, task.getUserId());

        WardrobeImageOptimizationAttemptEntity attempt = new WardrobeImageOptimizationAttemptEntity();
        attempt.setTaskId(task.getTaskId());
        attempt.setUserId(task.getUserId());
        attempt.setOperatorUserId(UserUtils.getUserId());
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        attempt.setErrorCode("");
        attempt.setErrorMessage("");
        attemptDao.save(attempt);
        return this.toVo(task, attempt);
    }

    private WardrobeImageOptimizationTaskEntity requireTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new BusinessException("任务ID不能为空");
        }
        WardrobeImageOptimizationTaskEntity task = taskDao.findByTaskIdInOwners(
                taskId, accessService.resolveFamilyOwnerIds());
        if (task == null) {
            throw new BusinessException("图片优化任务不存在");
        }
        WardrobeItemEntity item = itemDao.queryByIdInOwnerIds(task.getItemId(), accessService.resolveFamilyOwnerIds());
        accessService.requireManage(item);
        return task;
    }

    private WardrobeImageOptimizationAttemptEntity currentAttempt(WardrobeImageOptimizationTaskEntity task) {
        return attemptDao.findByTaskAndAttempt(task.getTaskId(), task.getCurrentAttemptNo(), task.getUserId());
    }

    private BusinessException activeTaskConflict(WardrobeImageOptimizationTaskEntity active) {
        return new BusinessException("该衣物已有图片优化任务正在处理中（任务 "
                + active.getTaskId() + "，状态 " + active.getStatus() + "）");
    }

    private WardrobeItemImageOptimizeTaskVo toVo(WardrobeImageOptimizationTaskEntity task,
                                                   WardrobeImageOptimizationAttemptEntity attempt) {
        WardrobeItemImageOptimizeTaskVo vo = new WardrobeItemImageOptimizeTaskVo();
        vo.setTaskId(task.getTaskId());
        vo.setItemId(task.getItemId());
        vo.setUserId(task.getUserId());
        vo.setRequesterUserId(task.getRequesterUserId());
        vo.setStatus(task.getStatus());
        vo.setItemImage(task.getResultDeletedTime() == null
                ? StringUtils.defaultString(task.getResultImageUrl()) : "");
        vo.setErrorMessage(StringUtils.defaultString(task.getErrorMessage()));
        vo.setAttemptNo(task.getCurrentAttemptNo());
        vo.setRetryable(StringUtils.equals(task.getStatus(), WardrobeImageOptimizationTaskStatus.FAILED.getCode()));
        vo.setDeadlineAt(attempt == null ? null : attempt.getDeadlineTime());
        return vo;
    }
}
