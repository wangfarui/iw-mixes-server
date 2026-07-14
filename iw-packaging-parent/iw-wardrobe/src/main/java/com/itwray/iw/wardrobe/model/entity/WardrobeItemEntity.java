package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 衣柜衣物表
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wardrobe_item")
public class WardrobeItemEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String itemName;

    private String itemImage;

    private Integer category;

    private Integer itemStyle;

    private String colorName;

    private String colorHex;

    private String seasonTags;

    private String sceneTags;

    private String styleTags;

    private String brand;

    private String size;

    private String material;

    private String purchaseChannel;

    private String storageLocation;

    private LocalDate purchaseDate;

    private BigDecimal price;

    private String customTags;

    private Integer status;

    private Integer wearCount;

    private LocalDate lastWearDate;

    private String remark;
}
