package com.itwray.iw.wardrobe.model.vo;

import com.itwray.iw.web.model.vo.DetailVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 衣物详情 VO
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "衣物详情VO")
public class WardrobeItemDetailVo extends WardrobeItemPageVo implements DetailVo {

    private String remark;
}
