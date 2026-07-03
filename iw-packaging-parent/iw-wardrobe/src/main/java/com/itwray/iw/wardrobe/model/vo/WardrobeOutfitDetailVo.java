package com.itwray.iw.wardrobe.model.vo;

import com.itwray.iw.web.model.vo.DetailVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 搭配详情 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "搭配详情VO")
public class WardrobeOutfitDetailVo extends WardrobeOutfitPageVo implements DetailVo {

    private String remark;
}
