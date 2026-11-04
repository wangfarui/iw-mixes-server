package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIdentityResolverTest {

    private final CodeIdentityResolver resolver = new CodeIdentityResolver(null);

    @Test
    void differentMemberEmailAndNameAreAConflict() {
        IdentityEntity email = mapping("EMAIL", "e1", 10L, false);
        IdentityEntity name = mapping("NAME", "n1", 20L, false);
        Map<String, IdentityEntity> index = resolver.mappingIndex(List.of(email, name));

        CodeIdentityResolver.Resolved resolved = resolver.resolve(index, "e1", "n1");

        assertEquals(null, resolved.memberId());
        assertFalse(resolved.ignored());
    }

    @Test
    void deletedOrIgnoredEmailDoesNotBlockAnActiveName() {
        IdentityEntity email = mapping("EMAIL", "e1", null, true);
        IdentityEntity name = mapping("NAME", "n1", 20L, false);
        Map<String, IdentityEntity> index = resolver.mappingIndex(List.of(email, name));

        CodeIdentityResolver.Resolved resolved = resolver.resolve(index, "e1", "n1");

        assertFalse(resolved.ignored());
        assertEquals(20L, resolved.memberId());
    }

    @Test
    void unmappedAuthorHasNoOwner() {
        CodeIdentityResolver.Resolved resolved = resolver.resolve(Map.of(), "unknown", "unknown-name");

        assertEquals(null, resolved.memberId());
        assertFalse(resolved.ignored());
        assertFalse(resolver.isIgnoredOrMapped(Map.of(), "unknown", "unknown-name"));
    }

    @Test
    void normalizesAndHashesIdentityValues() {
        assertEquals(CodeStatsSupport.emailHash(" Me@Example.com "), CodeStatsSupport.emailHash("me@example.com"));
        assertEquals(CodeStatsSupport.nameKey("  Zhang   Ming "), CodeStatsSupport.nameKey("zhang ming"));
        assertEquals("m***@example.com", CodeStatsSupport.maskEmail("Me@Example.com"));
        assertEquals("张*远", CodeStatsSupport.maskName("张明远"));
        assertEquals(64, CodeStatsSupport.emailHash("me@example.com").length());
    }

    @Test
    void branchNamesRoundTripAndTruncateToPayload() {
        List<String> branches = List.of("main", "feature/a");
        assertEquals(branches, CodeStatsSupport.fromBranchJson(CodeStatsSupport.toBranchJson(branches)));
        assertEquals("abc", CodeStatsSupport.truncate("abcdef", 3));
    }

    private IdentityEntity mapping(String type, String key, Long memberId, boolean ignored) {
        IdentityEntity entity = new IdentityEntity();
        entity.setIdentityType(type);
        entity.setIdentityKey(key);
        entity.setCodingUserId(memberId);
        entity.setIgnored(ignored);
        return entity;
    }
}
