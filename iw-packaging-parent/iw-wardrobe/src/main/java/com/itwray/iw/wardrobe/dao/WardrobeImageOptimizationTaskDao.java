package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeImageOptimizationTaskMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageOptimizationTaskEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageOptimizationTaskStatus;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WardrobeImageOptimizationTaskDao extends BaseDao<WardrobeImageOptimizationTaskMapper, WardrobeImageOptimizationTaskEntity> {

    public WardrobeImageOptimizationTaskEntity findActiveByItem(Integer itemId, Integer userId) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, userId)
                .in(WardrobeImageOptimizationTaskEntity::getStatus, List.of(
                        WardrobeImageOptimizationTaskStatus.QUEUED.getCode(),
                        WardrobeImageOptimizationTaskStatus.RUNNING.getCode()
                ))
                .orderByDesc(WardrobeImageOptimizationTaskEntity::getId)
                .last("limit 1")
                .one();
    }

    public WardrobeImageOptimizationTaskEntity findByFingerprint(Integer itemId, Integer userId, String fingerprint) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, userId)
                .eq(WardrobeImageOptimizationTaskEntity::getFingerprint, fingerprint)
                .last("limit 1")
                .one();
    }

    public WardrobeImageOptimizationTaskEntity findByTaskId(String taskId, Integer userId) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getTaskId, taskId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, userId)
                .last("limit 1")
                .one();
    }

    public WardrobeImageOptimizationTaskEntity findByTaskIdForUpdate(String taskId, Integer userId) {
        return this.baseMapper.selectByTaskIdForUpdate(taskId, userId);
    }

    public WardrobeImageOptimizationTaskEntity findLatestByItem(Integer itemId, Integer userId) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, userId)
                .orderByDesc(WardrobeImageOptimizationTaskEntity::getId)
                .last("limit 1")
                .one();
    }

    public WardrobeImageOptimizationTaskEntity findSucceededByResult(Integer itemId, Integer userId,
                                                                      String resultImageUrl) {
        return this.lambdaQuery()
                .eq(WardrobeImageOptimizationTaskEntity::getItemId, itemId)
                .eq(WardrobeImageOptimizationTaskEntity::getUserId, userId)
                .eq(WardrobeImageOptimizationTaskEntity::getStatus,
                        WardrobeImageOptimizationTaskStatus.SUCCEEDED.getCode())
                .eq(WardrobeImageOptimizationTaskEntity::getResultImageUrl, resultImageUrl)
                .orderByDesc(WardrobeImageOptimizationTaskEntity::getId)
                .last("limit 1")
                .one();
    }
}
