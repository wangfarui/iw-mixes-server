package com.itwray.iw.bookkeeping.support;

import com.itwray.iw.auth.model.enums.FamilyMemberRoleEnum;
import com.itwray.iw.auth.model.enums.ShareStateEnum;
import com.itwray.iw.auth.model.vo.FamilyMemberVo;
import com.itwray.iw.auth.model.vo.FamilySharedSavePolicyVo;
import com.itwray.iw.auth.service.AuthFamilyGroupService;
import com.itwray.iw.bookkeeping.model.entity.BookkeepingRecordsEntity;
import com.itwray.iw.bookkeeping.model.enums.RecordCategoryEnum;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookkeepingPayerPolicyTest {
    private AuthFamilyGroupService families;
    private BookkeepingPayerPolicy policy;
    private FamilySharedSavePolicyVo family;

    @BeforeEach void setUp() {
        UserUtils.setUserId(1);
        families = mock(AuthFamilyGroupService.class); policy = new BookkeepingPayerPolicy(families);
        family = new FamilySharedSavePolicyVo(); family.setCurrentGroupId(10);
        when(families.memberList(10)).thenReturn(List.of(member(1, FamilyMemberRoleEnum.MEMBER), member(2, FamilyMemberRoleEnum.MEMBER)));
    }
    @AfterEach void cleanUp() { UserUtils.removeUserId(); }

    @Test void defaultsToSelfAndPreservesOmittedPayerOnEdit() {
        BookkeepingRecordsEntity added = record(1);
        policy.apply(added, null, null, family, false); assertEquals(1, added.getExpenseUserId());
        BookkeepingRecordsEntity before = record(2); before.setShareState(ShareStateEnum.SHARED);
        BookkeepingRecordsEntity updated = record(1);
        policy.apply(updated, null, before, family, false);
        assertEquals(2, updated.getExpenseUserId()); assertEquals(1, updated.getUserId()); assertEquals(ShareStateEnum.SHARED, updated.getShareState());
    }
    @Test void delegationForcesSharingAndSupportsEditingFormerPersonalRecord() {
        BookkeepingRecordsEntity before = record(1); before.setGroupId(0);
        BookkeepingRecordsEntity updated = record(1); updated.setGroupId(0);
        policy.apply(updated, 2, before, family, false);
        assertEquals(10, updated.getGroupId()); assertEquals(ShareStateEnum.SHARED, updated.getShareState());
    }
    @Test void rejectsOtherFamiliesChildrenAndTaskPayerChanges() {
        assertThrows(RuntimeException.class, () -> policy.apply(record(1), 3, null, family, false));
        when(families.memberList(10)).thenReturn(List.of(member(1, FamilyMemberRoleEnum.CHILD), member(2, FamilyMemberRoleEnum.MEMBER)));
        assertThrows(RuntimeException.class, () -> policy.apply(record(1), 2, null, family, false));
        assertThrows(RuntimeException.class, () -> policy.apply(record(1), 2, record(1), family, true));
        family.setCurrentGroupId(0);
        assertThrows(RuntimeException.class, () -> policy.apply(record(1), 2, null, family, false));
    }
    @Test void departedRecordsKeepPayerAndCannotRestoreSharingOrMoveOwnership() {
        BookkeepingRecordsEntity before = record(2); before.setShareState(ShareStateEnum.LEFT_GROUP);
        BookkeepingRecordsEntity updated = record(1);
        policy.apply(updated, null, before, family, false);
        assertEquals(2, updated.getExpenseUserId()); assertEquals(ShareStateEnum.LEFT_GROUP, updated.getShareState());
        assertThrows(RuntimeException.class, () -> policy.apply(record(1), 1, before, family, false));
    }
    private BookkeepingRecordsEntity record(int payer) {
        BookkeepingRecordsEntity record = new BookkeepingRecordsEntity(); record.setUserId(1); record.setExpenseUserId(payer);
        record.setRecordCategory(RecordCategoryEnum.CONSUME); record.setGroupId(10); record.setShareState(ShareStateEnum.NOT_SHARED); return record;
    }
    private FamilyMemberVo member(int id, FamilyMemberRoleEnum role) {
        FamilyMemberVo member = new FamilyMemberVo(); member.setUserId(id); member.setRole(role); return member;
    }
}
