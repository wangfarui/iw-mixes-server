package com.itwray.iw.wardrobe.service;

import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.auth.model.vo.FamilyWardrobeMemberVo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 衣物所属、可见和可管理权限的唯一策略入口。
 */
public interface WardrobeItemAccessService {

    List<Integer> resolveVisibleOwnerIds(boolean queryOnlyMyself);

    List<Integer> resolveFamilyOwnerIds();

    Integer resolveOwnerForSave(Integer requestedOwnerUserId);

    void requireView(WardrobeItemEntity item);

    void requireManage(WardrobeItemEntity item);

    Map<Integer, FamilyWardrobeMemberVo> resolveVisibleMembers();

    Set<Integer> resolveManageableOwnerIds();
}
