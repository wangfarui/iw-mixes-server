package com.itwray.iw.auth.test;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.itwray.iw.auth.dao.AuthFamilyGroupDao;
import com.itwray.iw.auth.dao.AuthFamilyInviteDao;
import com.itwray.iw.auth.dao.AuthFamilyMemberDao;
import com.itwray.iw.auth.dao.AuthUserDao;
import com.itwray.iw.auth.model.entity.AuthFamilyMemberEntity;
import com.itwray.iw.auth.model.entity.AuthUserEntity;
import com.itwray.iw.auth.model.enums.FamilyMemberRoleEnum;
import com.itwray.iw.auth.model.enums.FamilyMemberStatusEnum;
import com.itwray.iw.auth.service.impl.AuthFamilyGroupServiceImpl;
import com.itwray.iw.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthFamilyGroupAccountDeletionTest {

    @Test
    void ownerMustTransferOrDissolveGroupBeforeDeletingAccount() {
        AuthFamilyMemberDao memberDao = mock(AuthFamilyMemberDao.class);
        LambdaQueryChainWrapper<AuthFamilyMemberEntity> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(memberDao.lambdaQuery()).thenReturn(query);
        stubQueryChain(query);

        AuthFamilyMemberEntity member = member(7, FamilyMemberRoleEnum.OWNER);
        when(query.one()).thenReturn(member);

        AuthFamilyGroupServiceImpl service = service(memberDao, mock(AuthUserDao.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.prepareAccountDeletion(12));
        assertEquals("您是家庭组群主，请先转让群主或解散家庭组", exception.getMessage());
    }

    @Test
    void normalMemberQuitsAndRunsTheSharedRecordLeaveFlow() {
        AuthFamilyMemberDao memberDao = mock(AuthFamilyMemberDao.class);
        AuthUserDao userDao = mock(AuthUserDao.class);
        LambdaQueryChainWrapper<AuthFamilyMemberEntity> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        LambdaUpdateChainWrapper<AuthFamilyMemberEntity> memberUpdate = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        LambdaUpdateChainWrapper<AuthUserEntity> userUpdate = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        when(memberDao.lambdaQuery()).thenReturn(query);
        when(memberDao.lambdaUpdate()).thenReturn(memberUpdate);
        when(userDao.lambdaUpdate()).thenReturn(userUpdate);
        stubQueryChain(query);
        stubUpdateChain(memberUpdate);
        stubUpdateChain(userUpdate);
        when(query.one()).thenReturn(member(8, FamilyMemberRoleEnum.MEMBER));
        when(memberUpdate.update()).thenReturn(true);
        when(userUpdate.update()).thenReturn(true);

        TestAuthFamilyGroupServiceImpl service = service(memberDao, userDao);

        assertDoesNotThrow(() -> service.prepareAccountDeletion(12));
        verify(memberUpdate).update();
        verify(userUpdate).update();
        assertEquals(3, service.leaveGroupId);
        assertEquals(List.of(12), service.leaveUserIds);
    }

    private TestAuthFamilyGroupServiceImpl service(AuthFamilyMemberDao memberDao, AuthUserDao userDao) {
        return new TestAuthFamilyGroupServiceImpl(
                mock(AuthFamilyGroupDao.class),
                memberDao,
                mock(AuthFamilyInviteDao.class),
                userDao);
    }

    private AuthFamilyMemberEntity member(Integer id, FamilyMemberRoleEnum role) {
        AuthFamilyMemberEntity member = new AuthFamilyMemberEntity();
        member.setId(id);
        member.setUserId(12);
        member.setGroupId(3);
        member.setRole(role);
        member.setStatus(FamilyMemberStatusEnum.NORMAL);
        return member;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubQueryChain(LambdaQueryChainWrapper<?> query) {
        doReturn(query).when(query).eq(any(SFunction.class), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubUpdateChain(LambdaUpdateChainWrapper<?> update) {
        doReturn(update).when(update).eq(any(SFunction.class), any());
        doReturn(update).when(update).set(any(SFunction.class), any());
    }

    private static class TestAuthFamilyGroupServiceImpl extends AuthFamilyGroupServiceImpl {

        private Integer leaveGroupId;
        private List<Integer> leaveUserIds;

        private TestAuthFamilyGroupServiceImpl(AuthFamilyGroupDao baseDao,
                                               AuthFamilyMemberDao familyMemberDao,
                                               AuthFamilyInviteDao familyInviteDao,
                                               AuthUserDao authUserDao) {
            super(baseDao, familyMemberDao, familyInviteDao, authUserDao);
        }

        @Override
        protected void sendFamilyMemberLeaveMessage(Integer groupId, List<Integer> userIdList) {
            this.leaveGroupId = groupId;
            this.leaveUserIds = userIdList;
        }
    }
}
