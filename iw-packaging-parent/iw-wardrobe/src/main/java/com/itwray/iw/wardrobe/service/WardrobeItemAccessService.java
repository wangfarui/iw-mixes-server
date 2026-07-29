package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;

import java.util.List;

/**
 * 衣物所属、可见和可管理权限的唯一策略入口。
 */
public interface WardrobeItemAccessService {

    List<Integer> resolveVisibleOwnerIds(boolean queryOnlyMyself);

    Integer resolveOwnerForSave(Integer requestedOwnerUserId);

    void requireView(WardrobeItemEntity item, boolean queryOnlyMyself);

    void requireManage(WardrobeItemEntity item);

    boolean canManage(WardrobeItemEntity item);
}
