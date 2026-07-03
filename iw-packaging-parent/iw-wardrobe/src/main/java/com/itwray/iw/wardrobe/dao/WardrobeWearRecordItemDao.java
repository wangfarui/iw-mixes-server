package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeWearRecordItemMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordItemEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

/**
 * 衣柜穿着记录衣物 DAO
 *
 * @author codex
 * @since 2026-07-02
 */
@Component
public class WardrobeWearRecordItemDao extends BaseDao<WardrobeWearRecordItemMapper, WardrobeWearRecordItemEntity> {
}
