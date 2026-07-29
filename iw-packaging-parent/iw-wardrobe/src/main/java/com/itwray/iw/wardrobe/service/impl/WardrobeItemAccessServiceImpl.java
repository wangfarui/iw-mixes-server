package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.auth.client.AuthFamilyGroupClient;
import com.itwray.iw.auth.model.vo.FamilyWardrobeAccessPolicyVo;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class WardrobeItemAccessServiceImpl implements WardrobeItemAccessService {

    private static final int ROLE_OWNER = 1;
    private static final int ROLE_PARENT = 2;
    private static final int ROLE_CHILD = 4;

    private final AuthFamilyGroupClient familyGroupClient;

    public WardrobeItemAccessServiceImpl(AuthFamilyGroupClient familyGroupClient) {
        this.familyGroupClient = familyGroupClient;
    }

    @Override
    public List<Integer> resolveVisibleOwnerIds(boolean queryOnlyMyself) {
        AccessContext context = this.currentContext();
        if (queryOnlyMyself || context.child()) {
            return List.of(context.userId());
        }
        return context.memberUserIds();
    }

    @Override
    public Integer resolveOwnerForSave(Integer requestedOwnerUserId) {
        AccessContext context = this.currentContext();
        Integer ownerUserId = requestedOwnerUserId == null ? context.userId() : requestedOwnerUserId;
        if (context.child() && !Objects.equals(ownerUserId, context.userId())) {
            throw new BusinessException("儿童只能维护自己的衣物");
        }
        if (!context.memberUserIds().contains(ownerUserId)) {
            throw new BusinessException("所属人不是当前家庭的有效成员");
        }
        return ownerUserId;
    }

    @Override
    public void requireView(WardrobeItemEntity item, boolean queryOnlyMyself) {
        if (item == null || !this.resolveVisibleOwnerIds(queryOnlyMyself).contains(item.getUserId())) {
            throw new BusinessException("衣物不存在或无权查看");
        }
    }

    @Override
    public void requireManage(WardrobeItemEntity item) {
        AccessContext context = this.currentContext();
        if (this.canManage(item, context)) {
            return;
        }
        throw new BusinessException("只能管理自己的衣物");
    }

    @Override
    public boolean canManage(WardrobeItemEntity item) {
        return this.canManage(item, this.currentContext());
    }

    private boolean canManage(WardrobeItemEntity item, AccessContext context) {
        return item != null
                && context.memberUserIds().contains(item.getUserId())
                && (Objects.equals(item.getUserId(), context.userId()) || context.ownerOrParent());
    }

    private AccessContext currentContext() {
        Integer userId = UserUtils.getUserId();
        FamilyWardrobeAccessPolicyVo policy = familyGroupClient.queryWardrobeAccessPolicy(userId);
        if (policy == null || policy.getMemberUserIds() == null || policy.getMemberUserIds().isEmpty()
                || !policy.getMemberUserIds().contains(userId)) {
            throw new BusinessException("无法确认当前家庭衣物权限，请稍后重试");
        }
        return new AccessContext(userId, policy.getCurrentUserRole(), policy.getMemberUserIds());
    }

    private record AccessContext(Integer userId, Integer role, List<Integer> memberUserIds) {
        boolean child() {
            return Objects.equals(role, ROLE_CHILD);
        }

        boolean ownerOrParent() {
            return Objects.equals(role, ROLE_OWNER) || Objects.equals(role, ROLE_PARENT);
        }
    }
}
