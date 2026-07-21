package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeItemImageOptimizeDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemImageOptimizeTaskVo;

public interface WardrobeImageOptimizationTaskService {

    WardrobeItemImageOptimizeTaskVo start(WardrobeItemImageOptimizeDto dto);

    WardrobeItemImageOptimizeTaskVo retry(String taskId);

    WardrobeItemImageOptimizeTaskVo get(String taskId);

    WardrobeItemImageOptimizeTaskVo getCurrent(Integer itemId);

    void assertSourceImageChangeAllowed(Integer itemId, String nextSourceImageUrl);

    void cancelForItemDeletion(Integer itemId);

    void markResultDeleted(Integer itemId, String resultImageUrl);
}
