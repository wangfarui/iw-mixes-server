package com.itwray.iw.wardrobe.dao;

import com.itwray.iw.wardrobe.mapper.WardrobeWearRecordMapper;
import com.itwray.iw.wardrobe.model.entity.WardrobeWearRecordEntity;
import com.itwray.iw.web.dao.BaseDao;
import org.springframework.stereotype.Component;

/**
 * 衣柜穿着记录 DAO
 *
 * @author codex
 * @since 2026-07-02
 */
@Component
public class WardrobeWearRecordDao extends BaseDao<WardrobeWearRecordMapper, WardrobeWearRecordEntity> {
}
