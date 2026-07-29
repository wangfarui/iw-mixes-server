package com.itwray.iw.wardrobe.model.vo;

import lombok.Data;

import java.util.List;

/** 标记已穿的提交结果；存在失效快照时先返回确认信息，不落库。 */
@Data
public class WardrobeMarkWornVo {

    private Integer recordId;

    private boolean confirmationRequired;

    private List<WardrobeOutfitItemVo> unavailableItems;
}
