package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.*;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeIdentityLifecycleTest {
    final ZhaogangCodeIdentityMapper mapper = mock(ZhaogangCodeIdentityMapper.class);
    final CodeIdentityResolver resolver = new CodeIdentityResolver(mapper);
    final Map<String, IdentityEntity> rows = new LinkedHashMap<>();
    final CodeStatsModule.ScopeInfo self = scope(CodeStatsModels.Scope.SELF);
    final CodeStatsModule.Context context = new CodeStatsModule.Context("fixture", 1, "Self", "", 100, "team", "https://example.com");
    final CodeStatsViewCache cache = mock(CodeStatsViewCache.class);
    final CodeStatsScopeDirectory directory = mock(CodeStatsScopeDirectory.class);
    final ZhaogangCodeCommitMapper commits = mock(ZhaogangCodeCommitMapper.class);

    CodeIdentityLifecycleTest() {
        when(mapper.insertDefault(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            String type=call.getArgument(2), key=call.getArgument(3);
            String compound=type+":"+key;
            if(rows.containsKey(compound)) return 0;
            IdentityEntity row = row(type,key,call.getArgument(1));
            row.setId((long)rows.size()+1); row.setScopeType("SELF"); row.setScopeId(call.getArgument(1));
            row.setSource("DEFAULT"); row.setAuthorName(call.getArgument(4));
            rows.put(compound,row); return 1;
        });
        when(mapper.selectByScope(anyLong(), eq("SELF"), anyLong())).thenAnswer(call -> rows.values().stream()
                .filter(row -> Objects.equals(row.getScopeId(), call.getArgument(2))).toList());
        when(mapper.selectById(anyLong())).thenAnswer(call -> rows.values().stream()
                .filter(row -> Objects.equals(row.getId(),call.getArgument(0))).findFirst().orElse(null));
        when(directory.resolve(context, CodeStatsModels.Scope.SELF,null)).thenReturn(self);
    }

    @Test
    void initializesTwoDefaultsAndDeleteDoesNotRecreateOrAffectOtherIdentity() {
        var profile = new CodeStatsViewCache.CachedProfile(1,"Self","",CodeStatsSupport.emailHash("self@example.com"),100,0);
        resolver.initializeDefaults(100,1,profile);
        assertEquals(2,rows.size());
        String before=resolver.signature(self);
        IdentityEntity name=rows.get("NAME:"+CodeStatsSupport.nameKey("Self"));
        service().delete(context,name.getId());
        resolver.initializeDefaults(100,1,profile);
        assertEquals(2,rows.size());
        assertEquals("DELETED",name.getSource());
        assertNotEquals(before,resolver.signature(self));
        var index=resolver.mappingIndex(resolver.mappings(self));
        assertNull(resolver.resolve(index,null,CodeStatsSupport.nameKey("Self")).memberId());
        assertEquals(1L,resolver.resolve(index,profile.emailHash(),"unknown").memberId());
    }

    @Test
    void canonicalTeamMappingsImmediatelyReflectMemberDeletion() {
        resolver.initializeDefaults(100,1,new CodeStatsViewCache.CachedProfile(1,"Self","","hash",100,0));
        var team=scope(CodeStatsModels.Scope.WORKBENCH_TEAM);
        assertEquals(resolver.signature(self),resolver.signature(team));
        String before=resolver.signature(team);
        service().delete(context,rows.values().iterator().next().getId());
        assertNotEquals(before,resolver.signature(team));
        verify(mapper,never()).selectByScope(anyLong(),eq("WORKBENCH_TEAM"),anyLong());
    }

    @Test
    void sameIdentityInTwoMembersAndCrossMemberNameEmailCannotDoubleCount() {
        var first=row("NAME","shared",1L);var second=row("NAME","shared",2L);
        var index=resolver.mappingIndex(List.of(first,second));
        assertNull(resolver.resolve(index,null,"shared").memberId());
        index=resolver.mappingIndex(List.of(row("EMAIL","mail",1L),row("NAME","name",2L)));
        assertNull(resolver.resolve(index,"mail","name").memberId());
    }

    @Test
    void multipleIdentityMatchesForSameOwnerResolveOnceAndIgnoredDoesNotBlockOrMatch() {
        var email=row("EMAIL","mail",1L);var name=row("NAME","name",1L);
        var index=resolver.mappingIndex(List.of(email,name));
        assertEquals(1L,resolver.resolve(index,"mail","name").memberId());
        email.setIgnored(true);
        index=resolver.mappingIndex(List.of(email,name));
        assertEquals(1L,resolver.resolve(index,"mail","name").memberId());
        assertNull(resolver.resolve(index,"mail","other").memberId());
    }

    @Test
    void anotherMemberCannotEditMyIdentity() {
        var foreign=row("EMAIL","x",2L);foreign.setId(99L);foreign.setScopeType("SELF");foreign.setScopeId(2L);
        rows.put("x",foreign);
        assertThrows(RuntimeException.class,()->service().delete(context,99));
        verify(mapper,never()).updateById(any(IdentityEntity.class));
    }

    private CodeIdentityService service() {
        return new CodeIdentityService(mapper,commits,directory,resolver,cache,mock(ObjectProvider.class),mock(CodeIdentityDefaults.class));
    }
    private CodeStatsModule.ScopeInfo scope(CodeStatsModels.Scope kind) {
        return new CodeStatsModule.ScopeInfo(kind,kind==CodeStatsModels.Scope.SELF?1:9,100,"t","https://example.com","t",
                List.of(new CodeStatsModule.MemberCredential(1,"Self","","fixture")),true);
    }
    private IdentityEntity row(String type,String key,Long owner) {
        var value=new IdentityEntity();value.setIdentityType(type);value.setIdentityKey(key);
        value.setCodingUserId(owner);value.setIgnored(false);value.setCodingTeamId(100L);return value;
    }
}
