package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.auth.client.AuthFamilyGroupClient;
import com.itwray.iw.auth.model.vo.FamilyWardrobeAccessPolicyVo;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WardrobeItemAccessServiceImplTest {

    @AfterEach
    void clearUserContext() {
        UserUtils.clearContext();
    }

    @Test
    void childCanOnlySeeAndAssignTheirOwnClothes() {
        UserUtils.setUserId(12);
        AuthFamilyGroupClient familyClient = mock(AuthFamilyGroupClient.class);
        when(familyClient.queryWardrobeAccessPolicy(12)).thenReturn(policy(4, List.of(12, 13)));
        WardrobeItemAccessService service = new WardrobeItemAccessServiceImpl(familyClient);

        assertEquals(List.of(12), service.resolveVisibleOwnerIds(false));
        assertEquals(12, service.resolveOwnerForSave(null));
        assertThrows(BusinessException.class, () -> service.resolveOwnerForSave(13));
        assertThrows(BusinessException.class, () -> service.requireManage(item(13)));
    }

    @Test
    void ordinaryMemberCanSeeFamilyButOnlyManageOwnClothes() {
        UserUtils.setUserId(12);
        AuthFamilyGroupClient familyClient = mock(AuthFamilyGroupClient.class);
        when(familyClient.queryWardrobeAccessPolicy(12)).thenReturn(policy(3, List.of(12, 13)));
        WardrobeItemAccessService service = new WardrobeItemAccessServiceImpl(familyClient);

        assertEquals(List.of(12, 13), service.resolveVisibleOwnerIds(false));
        assertEquals(13, service.resolveOwnerForSave(13));
        assertThrows(BusinessException.class, () -> service.requireManage(item(13)));
    }

    @Test
    void ownerAndParentCanManageCurrentFamilyMembersClothes() {
        UserUtils.setUserId(12);
        AuthFamilyGroupClient familyClient = mock(AuthFamilyGroupClient.class);
        when(familyClient.queryWardrobeAccessPolicy(12)).thenReturn(policy(2, List.of(12, 13)));
        WardrobeItemAccessService service = new WardrobeItemAccessServiceImpl(familyClient);

        service.requireManage(item(13));
        assertEquals(13, service.resolveOwnerForSave(13));
    }

    private static FamilyWardrobeAccessPolicyVo policy(Integer role, List<Integer> memberUserIds) {
        FamilyWardrobeAccessPolicyVo policy = new FamilyWardrobeAccessPolicyVo();
        policy.setCurrentGroupId(8);
        policy.setCurrentUserRole(role);
        policy.setMemberUserIds(memberUserIds);
        return policy;
    }

    private static WardrobeItemEntity item(Integer userId) {
        WardrobeItemEntity item = new WardrobeItemEntity();
        item.setId(7);
        item.setUserId(userId);
        return item;
    }
}
