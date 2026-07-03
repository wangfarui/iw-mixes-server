package com.itwray.iw.wardrobe.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.itwray.iw.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 穿着记录复制 DTO
 *
 * @author codex
 * @since 2026-07-03
 */
@Data
@Schema(name = "穿着记录复制DTO")
public class WardrobeWearRecordCopyDto {

    @NotNull(message = "记录id不能为空")
    @Schema(title = "记录id")
    private Integer id;

    @NotNull(message = "目标日期不能为空")
    @JsonFormat(pattern = DateUtils.DATE_FORMAT)
    @Schema(title = "目标日期")
    private LocalDate targetDate;

    @Schema(title = "记录类型(1计划 2已穿)")
    private Integer recordType;
}
