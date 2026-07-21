package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeImageOptimizationAttemptMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationAttemptEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class WardrobeImageOptimizationAttemptDao extends BaseDao<WardrobeImageOptimizationAttemptMapper, WardrobeImageOptimizationAttemptEntity> {

    public WardrobeImageOptimizationAttemptEntity findByTaskAndAttempt(String taskId, Integer attemptNo) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationAttemptEntity::getTaskId, taskId)
                .eq(WardrobeImageOptimizationAttemptEntity::getAttemptNo, attemptNo)
                .last("limit 1")
                .one();
    }

    public List<WardrobeImageOptimizationAttemptEntity> findRunnable(LocalDateTime now) {
        return this.lambdaQuery()
                .and(wrapper -> wrapper
                        .eq(WardrobeImageOptimizationAttemptEntity::getStatus, "queued")
                        .or(running -> running
                                .eq(WardrobeImageOptimizationAttemptEntity::getStatus, "running")
                                .isNotNull(WardrobeImageOptimizationAttemptEntity::getExternalTaskId)
                                .ne(WardrobeImageOptimizationAttemptEntity::getExternalTaskId, "")
                                .le(WardrobeImageOptimizationAttemptEntity::getNextPollTime, now)))
                .and(wrapper -> wrapper.isNull(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime)
                        .or().le(WardrobeImageOptimizationAttemptEntity::getClaimExpireTime, now))
                .orderByAsc(WardrobeImageOptimizationAttemptEntity::getId)
                .last("limit 10")
                .list();
    }

    public List<WardrobeImageOptimizationAttemptEntity> findExpired(LocalDateTime now) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationAttemptEntity::getStatus, "running")
                .le(WardrobeImageOptimizationAttemptEntity::getDeadlineTime, now)
                .orderByAsc(WardrobeImageOptimizationAttemptEntity::getId)
                .last("limit 20")
                .list();
    }
}
