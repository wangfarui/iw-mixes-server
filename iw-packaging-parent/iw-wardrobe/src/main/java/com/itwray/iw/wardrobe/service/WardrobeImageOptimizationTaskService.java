package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;

public interface WardrobeImageOptimizationTaskService {

    WardrobeItemImageOptimizeTaskVo start(WardrobeItemImageOptimizeDto dto);

    WardrobeItemImageOptimizeTaskVo retry(String taskId);

    WardrobeItemImageOptimizeTaskVo get(String taskId);

    WardrobeItemImageOptimizeTaskVo getCurrent(Integer itemId);

    void assertSourceImageChangeAllowed(Integer itemId, Integer ownerUserId, String nextSourceImageUrl);

    void assertOwnerChangeAllowed(Integer itemId, Integer ownerUserId);

    void transferOwnership(Integer itemId, Integer previousOwnerUserId, Integer nextOwnerUserId);

    void cancelForItemDeletion(Integer itemId, Integer ownerUserId);

    void markResultDeleted(Integer itemId, Integer ownerUserId, String resultImageUrl);
}
