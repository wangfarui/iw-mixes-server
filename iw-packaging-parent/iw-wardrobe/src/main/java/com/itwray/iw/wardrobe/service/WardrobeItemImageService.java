package com.itwray.iw.wardrobe.service;

import com.itwray.iw.web.model.vo.FileRecordVo;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 衣物原图与优化图服务。
 */
public interface WardrobeItemImageService {

    Map<Integer, String> getOptimizedImageUrlMap(Collection<Integer> itemIds, Collection<Integer> ownerUserIds);

    void applyCoverImages(List<WardrobeItemEntity> itemList);

    void replaceOptimizedImage(Integer itemId, FileRecordVo fileRecord);

    void transferOwnership(Integer itemId, Integer previousOwnerUserId, Integer nextOwnerUserId);

    void deleteOptimizedImage(Integer itemId, Integer ownerUserId);
}
