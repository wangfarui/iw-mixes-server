package com.itwray.iw.wardrobe.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 标记已穿 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "标记已穿DTO")
public class WardrobeMarkWornDto {

    @JsonFormat(pattern = DateUtils.DATE_FORMAT)
    @Schema(title = "穿着日期")
    private LocalDate wearDate;

    @Schema(title = "搭配id")
    private Integer outfitId;

    @Schema(title = "衣物id列表")
    private List<Integer> itemIds;

    @Schema(title = "场景标签")
    private String sceneTags;

    @Schema(title = "天气")
    private String weatherText;

    @Schema(title = "心情")
    private String moodText;

    @Schema(title = "备注")
    private String remark;
}
