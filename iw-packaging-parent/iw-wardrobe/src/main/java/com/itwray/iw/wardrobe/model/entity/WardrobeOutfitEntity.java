package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 衣柜搭配表
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wardrobe_outfit")
public class WardrobeOutfitEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String outfitName;

    private String coverImage;

    private String seasonTags;

    private String sceneTags;

    private String styleTags;

    private String customTags;

    private String colorSummary;

    private Integer itemCount;

    private Integer wearCount;

    private LocalDate lastWearDate;

    private Integer status;

    private String remark;
}
