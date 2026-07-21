package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationAttemptDao;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.dao.WardrobeItemDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageOptimizationTaskStatus;
import com.itwray.iw.wardrobe.service.WardrobeItemImageService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WardrobeImageOptimizationCompletionService {

    private final WardrobeImageOptimizationTaskDao taskDao;
    private final WardrobeImageOptimizationAttemptDao attemptDao;
    private final WardrobeItemDao itemDao;
    private final WardrobeItemImageService itemImageService;

    public WardrobeImageOptimizationCompletionService(WardrobeImageOptimizationTaskDao taskDao,
                                                       WardrobeImageOptimizationAttemptDao attemptDao,
                                                       WardrobeItemDao itemDao,
                                                       WardrobeItemImageService itemImageService) {
        this.taskDao = taskDao;
        this.attemptDao = attemptDao;
        this.itemDao = itemDao;
        this.itemImageService = itemImageService;
    }

    @Transactional
    public void complete(WardrobeImageOptimizationAttemptEntity claimed, FileRecordVo file,
                         String mimeType, String revisedPrompt, String externalTaskId) {
        WardrobeImageOptimizationTaskEntity task = this.requireCurrentTask(claimed);
        WardrobeImageOptimizationAttemptEntity attempt = this.requireClaim(claimed);
        if (itemDao.queryById(task.getItemId()) == null) {
            throw new BusinessException("衣物已删除，生成结果不再安装");
        }
        itemImageService.replaceOptimizedImage(task.getItemId(), file);
        LocalDateTime now = LocalDateTime.now();
        attempt.setStatus(WardrobeImageOptimizationTaskStatus.SUCCEEDED.getCode());
        attempt.setExternalTaskId(StringUtils.defaultIfBlank(externalTaskId, attempt.getExternalTaskId()));
        attempt.setResultImageUrl(file.getFileUrl());
        attempt.setResultMimeType(StringUtils.defaultString(mimeType));
        attempt.setRevisedPrompt(StringUtils.defaultString(revisedPrompt));
        attempt.setErrorMessage("");
        attempt.setCompleteTime(now);
        attempt.setClaimToken("");
        attempt.setClaimExpireTime(null);
        attemptDao.updateById(attempt);

        task.setStatus(WardrobeImageOptimizationTaskStatus.SUCCEEDED.getCode());
        task.setResultImageUrl(file.getFileUrl());
        task.setResultDeletedTime(null);
        task.setErrorMessage("");
        task.setCompleteTime(now);
        taskDao.updateById(task);
    }

    @Transactional
    public void defer(WardrobeImageOptimizationAttemptEntity claimed, String externalTaskId,
                      String revisedPrompt, String provider) {
        this.requireCurrentTask(claimed);
        WardrobeImageOptimizationAttemptEntity attempt = this.requireClaim(claimed);
        attempt.setExternalTaskId(StringUtils.defaultIfBlank(externalTaskId, attempt.getExternalTaskId()));
        attempt.setRevisedPrompt(StringUtils.defaultString(revisedPrompt));
        attempt.setProvider(StringUtils.defaultString(provider));
        attempt.setNextPollTime(LocalDateTime.now().plusSeconds(5));
        attempt.setClaimToken("");
        attempt.setClaimExpireTime(null);
        attemptDao.updateById(attempt);
    }

    @Transactional
    public void fail(WardrobeImageOptimizationAttemptEntity claimed, String errorMessage) {
        WardrobeImageOptimizationTaskEntity task;
        WardrobeImageOptimizationAttemptEntity attempt;
        try {
            task = this.requireCurrentTask(claimed);
            attempt = this.requireClaim(claimed);
        } catch (BusinessException ignored) {
            return;
        }
        String error = StringUtils.left(StringUtils.defaultIfBlank(errorMessage, "图片优化失败，请重试"), 500);
        LocalDateTime now = LocalDateTime.now();
        attempt.setStatus(WardrobeImageOptimizationTaskStatus.FAILED.getCode());
        attempt.setErrorMessage(error);
        attempt.setCompleteTime(now);
        attempt.setClaimToken("");
        attempt.setClaimExpireTime(null);
        attemptDao.updateById(attempt);
        task.setStatus(WardrobeImageOptimizationTaskStatus.FAILED.getCode());
        task.setErrorMessage(error);
        task.setCompleteTime(now);
        taskDao.updateById(task);
    }

    private WardrobeImageOptimizationTaskEntity requireCurrentTask(WardrobeImageOptimizationAttemptEntity claimed) {
        WardrobeImageOptimizationTaskEntity task = taskDao.findByTaskIdForUpdate(claimed.getTaskId(), claimed.getUserId());
        if (task == null || !claimed.getAttemptNo().equals(task.getCurrentAttemptNo())
                || !WardrobeImageOptimizationTaskStatus.RUNNING.getCode().equals(task.getStatus())) {
            throw new BusinessException("图片优化任务已失效");
        }
        return task;
    }

    private WardrobeImageOptimizationAttemptEntity requireClaim(WardrobeImageOptimizationAttemptEntity claimed) {
        WardrobeImageOptimizationAttemptEntity attempt = attemptDao.findByTaskAndAttempt(
                claimed.getTaskId(), claimed.getAttemptNo());
        if (attempt == null || !WardrobeImageOptimizationTaskStatus.RUNNING.getCode().equals(attempt.getStatus())
                || !StringUtils.equals(attempt.getClaimToken(), claimed.getClaimToken())) {
            throw new BusinessException("图片优化执行权已失效");
        }
        return attempt;
    }
}
