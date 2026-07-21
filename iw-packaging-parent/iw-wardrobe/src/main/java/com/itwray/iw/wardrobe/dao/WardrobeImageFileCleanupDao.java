package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeImageFileCleanupMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeImageFileCleanupEntity;
import com.itwray.iw.wardrobe.model.enums.WardrobeImageFileCleanupStatus;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class WardrobeImageFileCleanupDao extends BaseDao<WardrobeImageFileCleanupMapper, WardrobeImageFileCleanupEntity> {

    public List<WardrobeImageFileCleanupEntity> findRunnable(LocalDateTime now) {
        return this.lambdaQuery()
                .in(WardrobeImageFileCleanupEntity::getStatus,
                        WardrobeImageFileCleanupStatus.PENDING.getCode(),
                        WardrobeImageFileCleanupStatus.RETRYING.getCode())
                .le(WardrobeImageFileCleanupEntity::getNextRetryTime, now)
                .and(wrapper -> wrapper.isNull(WardrobeImageFileCleanupEntity::getClaimExpireTime)
                        .or().le(WardrobeImageFileCleanupEntity::getClaimExpireTime, now))
                .orderByAsc(WardrobeImageFileCleanupEntity::getNextRetryTime)
                .last("limit 10")
                .list();
    }
}
