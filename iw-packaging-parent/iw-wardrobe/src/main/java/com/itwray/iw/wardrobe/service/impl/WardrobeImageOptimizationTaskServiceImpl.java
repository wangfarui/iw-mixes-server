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
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WardrobeImageOptimizationTaskServiceImpl implements WardrobeImageOptimizationTaskService {

    private final WardrobeItemDao itemDao;
    private final WardrobeImageOptimizationTaskDao taskDao;
    private final WardrobeImageOptimizationAttemptDao attemptDao;
    private final WardrobeImageOptimizationPromptFactory promptFactory;

    public WardrobeImageOptimizationTaskServiceImpl(WardrobeItemDao itemDao,
                                                     WardrobeImageOptimizationTaskDao taskDao,
                                                     WardrobeImageOptimizationAttemptDao attemptDao,
                                                     WardrobeImageOptimizationPromptFactory promptFactory) {
        this.itemDao = itemDao;
        this.taskDao = taskDao;
        this.attemptDao = attemptDao;
        this.promptFactory = promptFactory;
    }

    @Override
    @Transactional
    public WardrobeItemImageOptimizeTaskVo start(WardrobeItemImageOptimizeDto dto) {
        if (dto == null || dto.getItemId() == null) {
            throw new BusinessException("衣物ID不能为空");
        }
        WardrobeItemEntity item = itemDao.queryById(dto.getItemId());
        if (item == null) {
            throw new BusinessException("衣物不存在或已删除");
        }
        if (StringUtils.isBlank(item.getItemImage())) {
            throw new BusinessException("请先上传衣物图片");
        }
        Integer userId = UserUtils.getUserId();
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
        task.setFingerprint(input.fingerprint());
        task.setSourceImageUrl(input.sourceImageUrl());
        task.setUserPrompt(input.userPrompt());
        task.setNormalizedPrompt(input.normalizedPrompt());
        task.setRuleVersion(WardrobeImageOptimizationPromptFactory.RULE_VERSION);
        task.setInputSnapshot(input.snapshotJson());
        task.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        task.setCurrentAttemptNo(1);
        task.setResultImageUrl("");
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
        WardrobeItemEntity item = itemDao.queryById(task.getItemId());
        if (item == null) {
            throw new BusinessException("衣物不存在或已删除");
        }
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
        if (itemId == null || itemDao.queryById(itemId) == null) {
            throw new BusinessException("衣物不存在或已删除");
        }
        WardrobeImageOptimizationTaskEntity task = taskDao.findLatestByItem(itemId, UserUtils.getUserId());
        return task == null ? null : this.toVo(task, this.currentAttempt(task));
    }

    @Override
    public void assertSourceImageChangeAllowed(Integer itemId, String nextSourceImageUrl) {
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(itemId, UserUtils.getUserId());
        if (active != null && !StringUtils.equals(active.getSourceImageUrl(),
                StringUtils.trimToEmpty(nextSourceImageUrl))) {
            throw new BusinessException("图片优化处理中，暂不能修改衣物源图");
        }
    }

    @Override
    @Transactional
    public void cancelForItemDeletion(Integer itemId) {
        WardrobeImageOptimizationTaskEntity active = taskDao.findActiveByItem(itemId, UserUtils.getUserId());
        if (active == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        active.setStatus(WardrobeImageOptimizationTaskStatus.CANCELLED.getCode());
        active.setErrorMessage("衣物已删除");
        active.setCompleteTime(now);
        taskDao.updateById(active);
        WardrobeImageOptimizationAttemptEntity attempt = this.currentAttempt(active);
        if (attempt != null) {
            attempt.setStatus(WardrobeImageOptimizationTaskStatus.CANCELLED.getCode());
            attempt.setErrorMessage("衣物已删除");
            attempt.setCompleteTime(now);
            attempt.setClaimToken("");
            attemptDao.updateById(attempt);
        }
    }

    @Override
    @Transactional
    public void markResultDeleted(Integer itemId, String resultImageUrl) {
        WardrobeImageOptimizationTaskEntity task = taskDao.findSucceededByResult(
                itemId, UserUtils.getUserId(), resultImageUrl);
        if (task == null) {
            return;
        }
        task.setResultDeletedTime(LocalDateTime.now());
        taskDao.updateById(task);
    }

    private WardrobeItemImageOptimizeTaskVo enqueueNextAttempt(WardrobeImageOptimizationTaskEntity task) {
        int attemptNo = task.getCurrentAttemptNo() == null ? 1 : task.getCurrentAttemptNo() + 1;
        task.setCurrentAttemptNo(attemptNo);
        task.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        task.setErrorMessage("");
        task.setResultImageUrl("");
        task.setResultDeletedTime(null);
        task.setCompleteTime(null);
        taskDao.updateById(task);

        WardrobeImageOptimizationAttemptEntity attempt = new WardrobeImageOptimizationAttemptEntity();
        attempt.setTaskId(task.getTaskId());
        attempt.setUserId(task.getUserId());
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(WardrobeImageOptimizationTaskStatus.QUEUED.getCode());
        attempt.setErrorMessage("");
        attemptDao.save(attempt);
        return this.toVo(task, attempt);
    }

    private WardrobeImageOptimizationTaskEntity requireTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new BusinessException("任务ID不能为空");
        }
        WardrobeImageOptimizationTaskEntity task = taskDao.findByTaskId(taskId, UserUtils.getUserId());
        if (task == null) {
            throw new BusinessException("图片优化任务不存在");
        }
        return task;
    }

    private WardrobeImageOptimizationAttemptEntity currentAttempt(WardrobeImageOptimizationTaskEntity task) {
        return attemptDao.findByTaskAndAttempt(task.getTaskId(), task.getCurrentAttemptNo());
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
