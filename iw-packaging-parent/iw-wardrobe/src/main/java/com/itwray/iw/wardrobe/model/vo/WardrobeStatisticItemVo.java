package com.itwray.iw.wardrobe.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 衣柜统计项 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "衣柜统计项VO")
public class WardrobeStatisticItemVo {

    private String name;

    private Integer value;
}
