package com.itwray.iw.auth.service.impl;

import com.itwray.iw.auth.mapper.KitchenDictionaryMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.model.entity.BaseDictEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static com.itwray.iw.auth.service.impl.KitchenDictionaryService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated kitchen dictionary contract tests; no Spring context or database. */
class KitchenDictionaryServiceTest {
    private KitchenDictionaryMapper mapper;
    private KitchenDictionaryService service;

    @BeforeEach
    void setup() {
        mapper = mock(KitchenDictionaryMapper.class);
        service = new KitchenDictionaryService(mapper);
        when(mapper.group(7)).thenReturn(3);
        when(mapper.lockUser(7)).thenReturn(3);
        when(mapper.lockGroup(3)).thenReturn(3);
        when(mapper.activeGroup(3)).thenReturn(3);
        when(mapper.role(3, 7)).thenReturn(1);
        when(mapper.lockRole(3, 7)).thenReturn(1);
        when(mapper.state(anyInt(), anyInt())).thenReturn(1L);
        when(mapper.lockState(anyInt(), anyInt())).thenReturn(1L);
        when(mapper.bump(anyInt(), anyInt())).thenReturn(1);
        when(mapper.list(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.lockList(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test
    void renameCreatesSuccessor() {
        BaseDictEntity old = row(10, 3010, "蔬菜", 0, 7, 0);
        when(mapper.find(10)).thenReturn(old);
        when(mapper.lockList(0, 0, 7)).thenReturn(List.of(old));
        when(mapper.retire(old)).thenReturn(1);
        doAnswer(call -> { ((BaseDictEntity) call.getArgument(0)).setId(11); return null; }).when(mapper).insert(any());
        Rename renamed = service.rename(7, 10, "蔬果", 0, version(0, 7, 1L), null);
        assertEquals(10, renamed.oldId());
        assertEquals(11, renamed.item().id());
        ArgumentCaptor<BaseDictEntity> created = ArgumentCaptor.forClass(BaseDictEntity.class);
        verify(mapper).insert(created.capture());
        assertEquals(10, created.getValue().getPreviousId());
        assertEquals("蔬果", created.getValue().getDictName());
    }

    @Test
    void unchangedNameDoesNotCreateVersion() {
        BaseDictEntity old = row(10, 3010, "蔬菜", 0, 7, 0);
        when(mapper.find(10)).thenReturn(old);
        when(mapper.lockList(0, 0, 7)).thenReturn(List.of(old));
        Rename renamed = service.rename(7, 10, " 蔬菜 ", 0, version(0, 7, 1L), null);
        assertEquals(10, renamed.item().id());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).retire(any());
    }

    @Test
    void copySkipsDisabledDuplicateFamilyNames() {
        BaseDictEntity personal = row(10, 3010, "豆制品", 0, 7, 0);
        BaseDictEntity familyDisabled = row(20, 3010, "豆制品", 1, 0, 3);
        familyDisabled.setDictStatus(0);
        when(mapper.lockList(0, 0, 7)).thenReturn(List.of(personal));
        when(mapper.lockList(1, 3, 0)).thenReturn(List.of(familyDisabled));
        Copied copied = service.copyPersonal(7, List.of(10), version(0, 7, 1L), version(1, 3, 1L), 3);
        assertTrue(copied.created().isEmpty());
        assertEquals(1, copied.skipped().size());
        verify(mapper, never()).insert(any());
    }

    @Test
    void orderRequiresCompleteIdSet() {
        BaseDictEntity a = row(10, 3010, "蔬菜", 0, 7, 0);
        BaseDictEntity b = row(11, 3010, "水果", 0, 7, 0);
        when(mapper.lockList(0, 0, 7)).thenReturn(List.of(a, b));
        assertThrows(BusinessException.class,
                () -> service.order(7, Type.CATEGORY, Scope.PERSONAL, List.of(10), version(0, 7, 1L), null));
        verify(mapper, never()).update(any());
    }

    @Test
    void initializeOnceDoesNotCopyAgain() {
        when(mapper.lockState(0, 7)).thenReturn(1L);
        service.initializePersonal(7);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).insertState(anyInt(), anyInt());
    }

    @Test
    void memberCannotWriteFamily() {
        when(mapper.lockRole(3, 7)).thenReturn(3);
        assertThrows(BusinessException.class,
                () -> service.create(7, Type.CATEGORY, Scope.FAMILY, "豆制品", version(1, 3, 1L), 3));
        verify(mapper, never()).insert(any());
    }

    private static BaseDictEntity row(int id, int type, String name, int scope, int user, int group) {
        BaseDictEntity row = new BaseDictEntity();
        row.setId(id); row.setDictType(type); row.setDictName(name); row.setDictStatus(1);
        row.setSort(1); row.setRevision(0); row.setDeleted(false); row.setParentId(0); row.setDictCode(1);
        row.setScopeType(scope); row.setUserId(user); row.setFamilyGroupId(group);
        return row;
    }

    private static String version(int scope, int owner, long revision) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(java.util.Arrays.deepToString(new Object[]{scope, owner, revision})
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
