package com.itwray.iw.wardrobe.service.impl;

import cn.hutool.core.util.IdUtil;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationAttemptDao;
import com.itwray.iw.wardrobe.dao.WardrobeImageOptimizationTaskDao;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageOptimizationTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WardrobeImageOptimizationClaimService {

    private static final String DEADLINE_ERROR = "图片优化超过15分钟截止时间，请重试";

    private final WardrobeImageOptimizationAttemptDao attemptDao;
    private final WardrobeImageOptimizationTaskDao taskDao;

    public WardrobeImageOptimizationClaimService(WardrobeImageOptimizationAttemptDao attemptDao,
                                                  WardrobeImageOptimizationTaskDao taskDao) {
        this.attemptDao = attemptDao;
        this.taskDao = taskDao;
    }

    @Transactional
    public WardrobeImageOptimizationAttemptEntity claimNext() {
        UserUtils.UserContextSnapshot snapshot = UserUtils.snapshotContext();
        UserUtils.setUserDataPermission(false);
        try {
            LocalDateTime now = LocalDateTime.now();
            this.failExpired(now);
            for (WardrobeImageOptimizationAttemptEntity candidate : attemptDao.findRunnable(now)) {
                boolean queued = WardrobeImageOptimizationTaskStatus.QUEUED.getCode().equals(candidate.getStatus());
                String token = IdUtil.fastSimpleUUID();
                var update = attemptDao.lambdaUpdate()
                        .eq(WardrobeImageOptimizationAttemptEntity::getId, candidate.getId())
                        .eq(WardrobeImageOptimizationAttemptEntity::getStatus, candidate.getStatus())
                        .and(wrapper -> wrapper.isNull(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime)
                                .or().le(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, now))
                        .set(WardrobeImageOptimizationAttemptEntity::getClaimToken, token)
                        .set(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, now.plusMinutes(2));
                if (queued) {
                    update.set(WardrobeImageOptimizationAttemptEntity::getStatus,
                                    WardrobeImageOptimizationTaskStatus.RUNNING.getCode())
                            .set(WardrobeImageOptimizationAttemptEntity::getStartTime, now)
                            .set(WardrobeImageOptimizationAttemptEntity::getDeadlineTime, now.plusMinutes(15));
                }
                if (!update.update()) {
                    continue;
                }
                WardrobeImageOptimizationTaskEntity task = taskDao.lambdaQuery()
                        .eq(WardrobeImageOptimizationTaskEntity::getTaskId, candidate.getTaskId())
                        .last("limit 1")
                        .one();
                if (task == null || !candidate.getAttemptNo().equals(task.getCurrentAttemptNo())) {
                    attemptDao.lambdaUpdate()
                            .eq(WardrobeImageOptimizationAttemptEntity::getId, candidate.getId())
                            .eq(WardrobeImageOptimizationAttemptEntity::getClaimToken, token)
                            .set(WardrobeImageOptimizationAttemptEntity::getStatus,
                                    WardrobeImageOptimizationTaskStatus.CANCELLED.getCode())
                            .set(WardrobeImageOptimizationAttemptEntity::getCompleteTime, now)
                            .set(WardrobeImageOptimizationAttemptEntity::getClaimToken, "")
                            .set(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, null)
                            .update();
                    continue;
                }
                if (queued) {
                    boolean taskClaimed = taskDao.lambdaUpdate()
                            .eq(WardrobeImageOptimizationTaskEntity::getId, task.getId())
                            .eq(WardrobeImageOptimizationTaskEntity::getCurrentAttemptNo, candidate.getAttemptNo())
                            .eq(WardrobeImageOptimizationTaskEntity::getStatus,
                                    WardrobeImageOptimizationTaskStatus.QUEUED.getCode())
                            .set(WardrobeImageOptimizationTaskEntity::getStatus,
                                    WardrobeImageOptimizationTaskStatus.RUNNING.getCode())
                            .update();
                    if (!taskClaimed) {
                        attemptDao.lambdaUpdate()
                                .eq(WardrobeImageOptimizationAttemptEntity::getId, candidate.getId())
                                .eq(WardrobeImageOptimizationAttemptEntity::getClaimToken, token)
                                .set(WardrobeImageOptimizationAttemptEntity::getStatus,
                                        WardrobeImageOptimizationTaskStatus.CANCELLED.getCode())
                                .set(WardrobeImageOptimizationAttemptEntity::getCompleteTime, now)
                                .set(WardrobeImageOptimizationAttemptEntity::getClaimToken, "")
                                .set(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, null)
                                .update();
                        continue;
                    }
                    candidate.setStatus(WardrobeImageOptimizationTaskStatus.RUNNING.getCode());
                    candidate.setStartTime(now);
                    candidate.setDeadlineTime(now.plusMinutes(15));
                }
                candidate.setClaimToken(token);
                candidate.setClaimExpireTime(now.plusMinutes(2));
                return candidate;
            }
            return null;
        } finally {
            UserUtils.restoreContext(snapshot);
        }
    }

    public boolean renewClaim(WardrobeImageOptimizationAttemptEntity claimed) {
        if (claimed == null || claimed.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean renewed = attemptDao.lambdaUpdate()
                .eq(WardrobeImageOptimizationAttemptEntity::getId, claimed.getId())
                .eq(WardrobeImageOptimizationAttemptEntity::getStatus,
                        WardrobeImageOptimizationTaskStatus.RUNNING.getCode())
                .eq(WardrobeImageOptimizationAttemptEntity::getClaimToken, claimed.getClaimToken())
                .set(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, now.plusMinutes(2))
                .update();
        if (renewed) {
            claimed.setClaimExpireTime(now.plusMinutes(2));
        }
        return renewed;
    }

    private void failExpired(LocalDateTime now) {
        for (WardrobeImageOptimizationAttemptEntity expired : attemptDao.findExpired(now)) {
            boolean updated = attemptDao.lambdaUpdate()
                    .eq(WardrobeImageOptimizationAttemptEntity::getId, expired.getId())
                    .eq(WardrobeImageOptimizationAttemptEntity::getStatus,
                            WardrobeImageOptimizationTaskStatus.RUNNING.getCode())
                    .set(WardrobeImageOptimizationAttemptEntity::getStatus,
                            WardrobeImageOptimizationTaskStatus.FAILED.getCode())
                    .set(WardrobeImageOptimizationAttemptEntity::getErrorMessage, DEADLINE_ERROR)
                    .set(WardrobeImageOptimizationAttemptEntity::getCompleteTime, now)
                    .set(WardrobeImageOptimizationAttemptEntity::getClaimToken, "")
                    .set(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, null)
                    .update();
            if (updated) {
                taskDao.lambdaUpdate()
                        .eq(WardrobeImageOptimizationTaskEntity::getTaskId, expired.getTaskId())
                        .eq(WardrobeImageOptimizationTaskEntity::getCurrentAttemptNo, expired.getAttemptNo())
                        .eq(WardrobeImageOptimizationTaskEntity::getStatus,
                                WardrobeImageOptimizationTaskStatus.RUNNING.getCode())
                        .set(WardrobeImageOptimizationTaskEntity::getStatus,
                                WardrobeImageOptimizationTaskStatus.FAILED.getCode())
                        .set(WardrobeImageOptimizationTaskEntity::getErrorMessage, DEADLINE_ERROR)
                        .set(WardrobeImageOptimizationTaskEntity::getCompleteTime, now)
                        .update();
            }
        }
    }
}
