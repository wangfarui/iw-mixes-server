package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeOutfitItemMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeOutfitItemEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

/**
 * 衣柜搭配衣物 DAO
 *
 * @author codex
 * @since 2026-07-02
 */
@Component
public class WardrobeOutfitItemDao extends BaseDao<WardrobeOutfitItemMapper, WardrobeOutfitItemEntity> {
}
