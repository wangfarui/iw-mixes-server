package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 穿着记录 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "穿着记录VO")
public class WardrobeWearRecordVo {

    private Integer id;

    private LocalDate wearDate;

    private Integer outfitId;

    private String outfitName;

    private String sceneTags;

    private String weatherText;

    private String moodText;

    private Integer recordType;

    private Integer itemCount;

    private String remark;

    private List<WardrobeOutfitItemVo> itemList;
}
