package com.itwray.iw.wardrobe.service.impl;

import com.itwray.iw.auth.client.AuthFamilyGroupClient;
import com.itwray.iw.auth.model.vo.FamilyWardrobeAccessPolicyVo;
import com.itwray.iw.auth.model.vo.FamilyWardrobeMemberVo;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import com.itwray.iw.wardrobe.model.entity.WardrobeItemEntity;
import com.itwray.iw.wardrobe.service.WardrobeItemAccessService;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequestScope
public class WardrobeItemAccessServiceImpl implements WardrobeItemAccessService {

    private final AuthFamilyGroupClient familyGroupClient;
    private AccessContext cachedContext;

    public WardrobeItemAccessServiceImpl(AuthFamilyGroupClient familyGroupClient) {
        this.familyGroupClient = familyGroupClient;
    }

    @Override
    public List<Integer> resolveVisibleOwnerIds(boolean queryOnlyMyself) {
        AccessContext context = this.currentContext();
        if (queryOnlyMyself || context.child() || context.queryOnlyMyself()) {
            return List.of(context.userId());
        }
        return context.memberUserIds();
    }

    @Override
    public List<Integer> resolveFamilyOwnerIds() {
        AccessContext context = this.currentContext();
        return context.child() ? List.of(context.userId()) : context.memberUserIds();
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
    public void requireView(WardrobeItemEntity item) {
        if (item == null || !this.resolveFamilyOwnerIds().contains(item.getUserId())) {
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
    public Map<Integer, FamilyWardrobeMemberVo> resolveVisibleMembers() {
        return this.currentContext().members().stream()
                .filter(member -> member.getUserId() != null)
                .collect(Collectors.toMap(FamilyWardrobeMemberVo::getUserId, Function.identity(), (left, right) -> left));
    }

    @Override
    public Set<Integer> resolveManageableOwnerIds() {
        AccessContext context = this.currentContext();
        return context.canManageFamilyWardrobe()
                ? Set.copyOf(context.memberUserIds()) : Set.of(context.userId());
    }

    private boolean canManage(WardrobeItemEntity item, AccessContext context) {
        return item != null
                && context.memberUserIds().contains(item.getUserId())
                && (Objects.equals(item.getUserId(), context.userId()) || context.canManageFamilyWardrobe());
    }

    private AccessContext currentContext() {
        if (cachedContext != null) {
            return cachedContext;
        }
        Integer userId = UserUtils.getUserId();
        try {
            FamilyWardrobeAccessPolicyVo policy = familyGroupClient.queryWardrobeAccessPolicy(userId);
            if (policy == null || policy.getMemberUserIds() == null || policy.getMemberUserIds().isEmpty()
                    || !policy.getMemberUserIds().contains(userId)) {
                cachedContext = personalContext(userId);
                return cachedContext;
            }
            List<FamilyWardrobeMemberVo> members = policy.getMembers() == null ? List.of() : policy.getMembers();
            cachedContext = new AccessContext(userId, policy.isChild(), policy.isCanManageFamilyWardrobe(),
                    policy.isQueryOnlyMyself(),
                    List.copyOf(policy.getMemberUserIds()), members);
            return cachedContext;
        } catch (RuntimeException ignored) {
            cachedContext = personalContext(userId);
            return cachedContext;
        }
    }

    private AccessContext personalContext(Integer userId) {
        FamilyWardrobeMemberVo member = new FamilyWardrobeMemberVo();
        member.setUserId(userId);
        member.setName("我");
        return new AccessContext(userId, false, false, true, List.of(userId), List.of(member));
    }

    private record AccessContext(Integer userId, boolean child, boolean canManageFamilyWardrobe,
                                 boolean queryOnlyMyself,
                                 List<Integer> memberUserIds, List<FamilyWardrobeMemberVo> members) {
    }
}
