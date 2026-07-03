package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.dto.WardrobeMarkWornDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordAddDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordCopyDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordMonthDto;
import com.itwray.iw.wardrobe.model.dto.WardrobeWearRecordUpdateDto;
import com.itwray.iw.wardrobe.model.vo.WardrobeWearRecordVo;

import java.util.List;

/**
 * 穿着记录服务
 *
 * @author codex
 * @since 2026-07-02
 */
public interface WardrobeWearRecordService {

    Integer add(WardrobeWearRecordAddDto dto);

    void update(WardrobeWearRecordUpdateDto dto);

    void delete(Integer id);

    WardrobeWearRecordVo detail(Integer id);

    Integer copy(WardrobeWearRecordCopyDto dto);

    List<WardrobeWearRecordVo> month(WardrobeWearRecordMonthDto dto);

    List<WardrobeWearRecordVo> today();

    List<WardrobeWearRecordVo> recent(int limit);

    Integer markWorn(WardrobeMarkWornDto dto);

    void markRecordWorn(Integer id);
}
