package com.itwray.iw.web.kitchen;

import com.itwray.iw.web.model.entity.BaseDictEntity;
import java.util.Collection;
import java.util.List;

/** Eat calls this only after authorizing the actual business owner. */
public interface KitchenDictionaryAccess {
    record Context(Integer ownerUserId, Integer familyGroupId, String personalScopeVersion,
                   String familyScopeVersion, String contextToken, List<BaseDictEntity> items) { }

    Context context(Integer ownerUserId);

    record Identity(Integer userId, Integer familyGroupId, boolean manager, boolean child) { }

    /** Fresh locked membership for kitchen authorization policy, not an authorization grant. */
    List<Identity> lockOwners(Collection<Integer> ownerUserIds);

    /** Participates in the business writer transaction; does not follow rename successors. */
    List<BaseDictEntity> lockSelectable(Integer ownerUserId, String contextToken, Collection<Integer> sourceIds);

    static boolean supports(Integer type) {
        return Integer.valueOf(3010).equals(type) || Integer.valueOf(3011).equals(type);
    }
}
