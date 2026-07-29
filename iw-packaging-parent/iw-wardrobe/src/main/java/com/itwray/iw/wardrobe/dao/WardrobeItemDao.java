package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeItemMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

/**
 * 衣柜衣物 DAO
 *
 * @author codex
 * @since 2026-07-02
 */
@Component
public class WardrobeItemDao extends BaseDao<WardrobeItemMapper, WardrobeItemEntity> {

    public WardrobeItemEntity queryByIdInOwnerIds(Integer id, java.util.List<Integer> ownerUserIds) {
        WardrobeItemEntity item = this.lambdaQuery()
                .eq(WardrobeItemEntity::getId, id)
                .in(WardrobeItemEntity::getUserId, ownerUserIds)
                .one();
        if (item == null) {
            throw new com.itwray.iw.web.exception.BusinessException("衣物不存在或无权查看");
        }
        return item;
    }
}
