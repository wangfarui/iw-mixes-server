package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeItemAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemBatchAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeItemUpdateDto;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeItemPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeTagSummaryVo;
import com.itwray.iw.web.model.vo.PageVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 衣物服务
 *
 * @author codex
 * @since 2026-07-02
 */
public interface WardrobeItemService {

    Integer add(WardrobeItemAddDto dto);

    List<Integer> batchAdd(WardrobeItemBatchAddDto dto);

    void update(WardrobeItemUpdateDto dto);

    void delete(Integer id);

    void deleteOptimizedImage(Integer id);

    PageVo<WardrobeItemPageVo> page(WardrobeItemPageDto dto);

    WardrobeItemDetailVo detail(Integer id);

    WardrobeTagSummaryVo tagSummary();

    List<WardrobeItemEntity> queryActiveItemsByIds(List<Integer> itemIds);

    void increaseWearCount(List<Integer> itemIds, LocalDate wearDate);
}
