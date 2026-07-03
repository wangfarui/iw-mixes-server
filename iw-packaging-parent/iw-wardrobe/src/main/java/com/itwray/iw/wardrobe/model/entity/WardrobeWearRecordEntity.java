package com.itwray.iw.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itwray.iw.web.model.entity.UserEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 衣柜穿着记录表
 *
 * @author codex
 * @since 2026-07-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wardrobe_wear_record")
public class WardrobeWearRecordEntity extends UserEntity<Integer> {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private LocalDate wearDate;

    private Integer outfitId;

    private String outfitName;

    private String sceneTags;

    private String weatherText;

    private String moodText;

    private Integer recordType;

    private Integer itemCount;

    private String remark;
}
