package com.itwray.iw.wardrobe.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 穿着记录月份查询 DTO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@Schema(name = "穿着记录月份查询DTO")
public class WardrobeWearRecordMonthDto {

    @Schema(title = "月份，格式 yyyy-MM")
    private String month;
}
