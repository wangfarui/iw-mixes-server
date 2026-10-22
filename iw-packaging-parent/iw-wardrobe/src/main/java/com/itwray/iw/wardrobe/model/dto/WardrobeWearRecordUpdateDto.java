package com.itwray.iw.wardrobe.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.web.model.dto.AddDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 穿着记录新增 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "穿着记录新增DTO")
public class WardrobeWearRecordUpdateDto implements com.itwray.iw.web.model.dto.UpdateDto {

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

    @Schema(title = "记录类型(1计划 2已穿)")
    private Integer recordType;

    @Size(max = 255, message = "备注不能超过255个字符")
    @Schema(title = "备注")
    private String remark;

    /** Present only on create; names are resolved by the server. */
    private com.fasterxml.jackson.databind.JsonNode dictionarySelection;
    /** JSON absence and null intentionally have different meanings. */
    private com.fasterxml.jackson.databind.JsonNode dictionaryChanges;
    private java.util.Map<String,String> dictionaryContext;
    @jakarta.validation.constraints.NotNull(message="id不能为空")
    private Integer id;
    private java.util.List<com.itwray.iw.wardrobe.dictionary.WardrobeDetailEditor.Selection> items;
    private String itemSetVersion;
    private Boolean replaceOutfit;
}
