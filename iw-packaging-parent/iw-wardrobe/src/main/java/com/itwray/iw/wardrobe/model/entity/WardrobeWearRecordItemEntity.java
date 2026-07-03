package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 衣柜穿着记录衣物表
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wardrobe_wear_record_item")
public class WardrobeWearRecordItemEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer recordId;

    private Integer itemId;

    private String itemName;

    private String itemImage;

    private Integer category;

    private Integer sort;
}
