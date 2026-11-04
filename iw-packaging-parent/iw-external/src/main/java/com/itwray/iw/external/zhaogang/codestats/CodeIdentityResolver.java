package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
class CodeIdentityResolver {

    static final String EMAIL = "EMAIL";
    static final String NAME = "NAME";
    static final String AUTO = "AUTO";
    static final String MANUAL = "MANUAL";
    static final String SELF = "SELF";

    private final ZhaogangCodeIdentityMapper mapper;

    CodeIdentityResolver(ZhaogangCodeIdentityMapper mapper) {
        this.mapper = mapper;
    }

    static final String DELETED = "DELETED";
    static final String SELECTION_VERSION = "identity-first-v2";

    /** SELF 为成员的唯一身份集合；团队只读取当前成员集合，不再独立复制身份。 */
    List<IdentityEntity> mappings(CodeStatsModule.ScopeInfo scope) {
        if (scope.scope() == CodeStatsModels.Scope.SELF) {
            return mapper.selectByScope(scope.codingTeamId(), SELF, scope.scopeId());
        }
        return scope.members().stream().flatMap(member ->
                mapper.selectByScope(scope.codingTeamId(), SELF, member.userId()).stream()).toList();
    }

    String signature(CodeStatsModule.ScopeInfo scope) {
        return signature(mappings(scope));
    }

    String signature(List<IdentityEntity> mappings) {
        String values = mappings.stream().map(item -> item.getScopeId() + ":" + item.getIdentityType() + ":"
                + item.getIdentityKey() + ":" + item.getCodingUserId() + ":" + item.getIgnored() + ":" + item.getSource())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        return CodeStatsSupport.sha256(SELECTION_VERSION + "|" + values);
    }

    void initializeDefaults(long codingTeamId, long userId, CodeStatsViewCache.CachedProfile profile) {
        if (profile == null || profile.userId() != userId || profile.teamId() != codingTeamId) {
            throw new com.itwray.iw.external.zhaogang.CodingOpenApiException("当前令牌无法取得本人 CODING 身份，请检查绑定");
        }
        if (profile.name() == null || profile.name().isBlank() || profile.emailHash() == null
                || profile.emailHash().isBlank()) {
            throw new com.itwray.iw.external.zhaogang.CodingOpenApiException("CODING 未返回完整的作者名和邮箱，请检查账户资料");
        }
        // 唯一键 + INSERT IGNORE 保证并发初始化不覆盖用户绑定或已删除身份的墓碑。
        mapper.insertDefault(codingTeamId, userId, EMAIL, profile.emailHash(), "已绑定邮箱");
        mapper.insertDefault(codingTeamId, userId, NAME, CodeStatsSupport.nameKey(profile.name()),
                CodeStatsSupport.maskName(profile.name()));
    }

    Map<String, IdentityEntity> mappingIndex(List<IdentityEntity> mappings) {
        Map<String, IdentityEntity> result = new HashMap<>();
        for (IdentityEntity mapping : mappings) {
            if (Boolean.TRUE.equals(mapping.getIgnored()) || DELETED.equals(mapping.getSource())) continue;
            String key = key(mapping.getIdentityType(), mapping.getIdentityKey());
            IdentityEntity existing = result.get(key);
            if (existing == null) result.put(key, mapping);
            else if (!java.util.Objects.equals(existing.getCodingUserId(), mapping.getCodingUserId())) {
                IdentityEntity conflict = new IdentityEntity();
                conflict.setIdentityType(mapping.getIdentityType());
                conflict.setIdentityKey(mapping.getIdentityKey());
                conflict.setIgnored(false);
                result.put(key, conflict); // 同名跨成员或邮箱冲突，拒绝重复归属。
            }
        }
        return result;
    }

    Resolved resolve(Map<String, IdentityEntity> mappings, String emailHash, String nameKey) {
        IdentityEntity email = emailHash == null ? null : mappings.get(key(EMAIL, emailHash));
        IdentityEntity name = nameKey == null || nameKey.isBlank() ? null : mappings.get(key(NAME, nameKey));
        if ((email != null && email.getCodingUserId() == null) || (name != null && name.getCodingUserId() == null)) {
            return Resolved.UNMAPPED;
        }
        if (email != null && name != null
                && !java.util.Objects.equals(email.getCodingUserId(), name.getCodingUserId())) return Resolved.UNMAPPED;
        IdentityEntity mapping = email != null ? email : name;
        return mapping == null ? Resolved.UNMAPPED : new Resolved(mapping.getCodingUserId(), false);
    }

    boolean isIgnoredOrMapped(Map<String, IdentityEntity> mappings, String emailHash, String nameKey) {
        Resolved resolved = resolve(mappings, emailHash, nameKey);
        return resolved.memberId() != null || resolved.ignored();
    }

    private String key(String type, String identityKey) {
        return type + ":" + identityKey;
    }

    record Resolved(Long memberId, boolean ignored) {
        static final Resolved UNMAPPED = new Resolved(null, false);
        static final Resolved IGNORED = new Resolved(null, true);
    }
}
