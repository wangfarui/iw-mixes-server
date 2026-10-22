package com.itwray.iw.web.wardrobe;

import com.itwray.iw.web.model.entity.BaseDictEntity;
import java.util.Collection;
import java.util.List;

/** Wardrobe calls this only after authorizing the actual business owner. */
public interface WardrobeDictionaryAccess {
    record Context(Integer ownerUserId, Integer familyGroupId, String personalScopeVersion,
                   String familyScopeVersion, String contextToken, List<BaseDictEntity> items) { }

    Context context(Integer ownerUserId);
    record Identity(Integer userId, Integer familyGroupId, boolean manager, boolean child) { }
    /** Fresh locked membership for wardrobe's own authorization policy, not an authorization grant. */
    List<Identity> lockOwners(Collection<Integer> ownerUserIds);

    /** Participates in the business writer transaction; does not follow rename successors. */
    List<BaseDictEntity> lockSelectable(Integer ownerUserId, String contextToken, Collection<Integer> sourceIds);

    static boolean supports(Integer type) {
        return type != null && type >= 5002 && type <= 5006;
    }
}
