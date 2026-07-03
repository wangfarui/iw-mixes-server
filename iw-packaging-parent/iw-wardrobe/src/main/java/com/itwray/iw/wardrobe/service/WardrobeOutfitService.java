package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitPageDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitSuggestDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeOutfitUpdateDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitDetailVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitPageVo;
import com.itwray.iw.wardrobe.model.vo.WardrobeOutfitSuggestionVo;
import com.itwray.iw.web.model.vo.PageVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 搭配服务
 *
 * @author codex
 * @since 2026-07-02
 */
public interface WardrobeOutfitService {

    Integer add(WardrobeOutfitAddDto dto);

    void update(WardrobeOutfitUpdateDto dto);

    void delete(Integer id);

    Integer copy(Integer id);

    PageVo<WardrobeOutfitPageVo> page(WardrobeOutfitPageDto dto);

    WardrobeOutfitDetailVo detail(Integer id);

    List<WardrobeOutfitSuggestionVo> suggest(WardrobeOutfitSuggestDto dto);

    Integer markWorn(WardrobeMarkWornDto dto);

    void increaseWearCount(Integer outfitId, LocalDate wearDate);
}
