package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.util.IdUtil;
import com.itwray.iw.web.service.FileService;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.WardrobeImageFileCleanupDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageFileCleanupEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageFileCleanupStatus;
import com.itwray.iw.wardrobe.service.WardrobeImageFileCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class WardrobeImageFileCleanupServiceImpl implements WardrobeImageFileCleanupService {

    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));

    private final WardrobeImageFileCleanupDao cleanupDao;
    private final FileService fileService;

    public WardrobeImageFileCleanupServiceImpl(WardrobeImageFileCleanupDao cleanupDao, FileService fileService) {
        this.cleanupDao = cleanupDao;
        this.fileService = fileService;
    }

    @Override
    @Transactional
    public void enqueue(String taskId, Integer itemId, Integer attemptNo, String fileUrl, String reason,
                        Integer userId) {
        this.saveCleanup(taskId, itemId, attemptNo, fileUrl, reason, userId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueRequiresNew(String taskId, Integer itemId, Integer attemptNo, String fileUrl, String reason,
                                   Integer userId) {
        this.saveCleanup(taskId, itemId, attemptNo, fileUrl, reason, userId);
    }

    @Override
    public boolean processNext() {
        WardrobeImageFileCleanupEntity cleanup = this.claimNext();
        if (cleanup == null) {
            return false;
        }
        UserUtils.UserContextSnapshot snapshot = UserUtils.snapshotContext();
        UserUtils.setUserId(cleanup.getUserId());
        try {
            fileService.delete(cleanup.getFileUrl());
            cleanup.setStatus(WardrobeImageFileCleanupStatus.SUCCEEDED.getCode());
            cleanup.setCompleteTime(LocalDateTime.now());
            cleanup.setClaimToken("");
            cleanup.setClaimExpireTime(null);
            cleanup.setLastError("");
            cleanupDao.updateById(cleanup);
        } catch (Exception e) {
            this.recordFailure(cleanup, e);
        } finally {
            UserUtils.restoreContext(snapshot);
        }
        return true;
    }

    private void saveCleanup(String taskId, Integer itemId, Integer attemptNo, String fileUrl, String reason,
                             Integer userId) {
        if (StringUtils.isBlank(fileUrl)) {
            return;
        }
        WardrobeImageFileCleanupEntity cleanup = new WardrobeImageFileCleanupEntity();
        cleanup.setTaskId(StringUtils.defaultString(taskId));
        cleanup.setItemId(itemId);
        cleanup.setAttemptNo(attemptNo);
        cleanup.setFileUrl(fileUrl);
        cleanup.setReason(reason);
        cleanup.setStatus(WardrobeImageFileCleanupStatus.PENDING.getCode());
        cleanup.setRetryCount(0);
        cleanup.setNextRetryTime(LocalDateTime.now());
        cleanup.setLastError("");
        cleanup.setUserId(userId);
        cleanupDao.save(cleanup);
    }

    @Transactional
    protected WardrobeImageFileCleanupEntity claimNext() {
        UserUtils.UserContextSnapshot snapshot = UserUtils.snapshotContext();
        UserUtils.setUserDataPermission(false);
        try {
            LocalDateTime now = LocalDateTime.now();
            for (WardrobeImageFileCleanupEntity candidate : cleanupDao.findRunnable(now)) {
                String token = IdUtil.fastSimpleUUID();
                boolean claimed = cleanupDao.lambdaUpdate()
                        .eq(WardrobeImageFileCleanupEntity::getId, candidate.getId())
                        .in(WardrobeImageFileCleanupEntity::getStatus,
                                WardrobeImageFileCleanupStatus.PENDING.getCode(),
                                WardrobeImageFileCleanupStatus.RETRYING.getCode())
                        .and(wrapper -> wrapper.isNull(WardrobeImageFileCleanupEntity::getClaimExpireTime)
                                .or().le(WardrobeImageFileCleanupEntity::getClaimExpireTime, now))
                        .set(WardrobeImageFileCleanupEntity::getClaimToken, token)
                        .set(WardrobeImageFileCleanupEntity::getClaimExpireTime, now.plusMinutes(2))
                        .set(WardrobeImageFileCleanupEntity::getStatus,
                                WardrobeImageFileCleanupStatus.RETRYING.getCode())
                        .set(WardrobeImageFileCleanupEntity::getLastAttemptTime, now)
                        .update();
                if (claimed) {
                    candidate.setClaimToken(token);
                    candidate.setClaimExpireTime(now.plusMinutes(2));
                    candidate.setStatus(WardrobeImageFileCleanupStatus.RETRYING.getCode());
                    candidate.setLastAttemptTime(now);
                    return candidate;
                }
            }
            return null;
        } finally {
            UserUtils.restoreContext(snapshot);
        }
    }

    private void recordFailure(WardrobeImageFileCleanupEntity cleanup, Exception e) {
        int failures = cleanup.getRetryCount() == null ? 1 : cleanup.getRetryCount() + 1;
        cleanup.setRetryCount(failures);
        cleanup.setLastError(StringUtils.left(StringUtils.defaultString(e.getMessage(), e.getClass().getSimpleName()), 500));
        cleanup.setClaimToken("");
        cleanup.setClaimExpireTime(null);
        if (failures > RETRY_DELAYS.size()) {
            cleanup.setStatus(WardrobeImageFileCleanupStatus.MANUAL_REQUIRED.getCode());
            cleanup.setManualRequiredTime(LocalDateTime.now());
            log.error("Wardrobe image OSS cleanup requires manual action, cleanupId={}, url={}",
                    cleanup.getId(), cleanup.getFileUrl(), e);
        } else {
            cleanup.setStatus(WardrobeImageFileCleanupStatus.PENDING.getCode());
            cleanup.setNextRetryTime(LocalDateTime.now().plus(RETRY_DELAYS.get(failures - 1)));
            log.warn("Wardrobe image OSS cleanup failed, cleanupId={}, retry={}", cleanup.getId(), failures, e);
        }
        cleanupDao.updateById(cleanup);
    }
}
