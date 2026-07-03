package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeItemMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

/**
 * 衣柜衣物 DAO
 *
 * @author codex
 * @since 2026-07-02
 */
@Component
public class WardrobeItemDao extends BaseDao<WardrobeItemMapper, WardrobeItemEntity> {
}
