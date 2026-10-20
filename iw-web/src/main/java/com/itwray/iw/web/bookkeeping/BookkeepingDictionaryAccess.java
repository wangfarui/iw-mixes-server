package com.itwray.iw.web.bookkeeping;

import com.itwray.iw.web.model.entity.BaseDictEntity;
import java.util.Collection;
import java.util.List;

/** Auth implements this boundary; callers keep validation and writes in one transaction.
 * Lock order: task, bill, family/group/member, owner/scope, dictionary IDs ascending.
 */
public interface BookkeepingDictionaryAccess {
    List<BaseDictEntity> options(Integer userId, Integer dictType);
    /** Lock references in ascending ID order and revalidate current ownership/status/type. */
    List<BaseDictEntity> lockSelectable(Integer userId, Integer dictType, Collection<Integer> ids);
    /** Resolve a future configuration along the same-owner rename chain only. */
    BaseDictEntity resolveSuccessor(Integer userId, Integer dictType, Integer sourceId);
    /** Restricted to IDs already authorized by the caller through a visible bill. */
    List<BaseDictEntity> sources(Collection<Integer> authorizedSourceIds);
}
