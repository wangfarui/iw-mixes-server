package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import org.springframework.stereotype.Component;

@Component
class CodeIdentityDefaults {
    private final CodingOpenApiPort coding;
    private final CodeIdentityResolver identities;
    private final CodeStatsViewCache cache;

    CodeIdentityDefaults(CodingOpenApiPort coding, CodeIdentityResolver identities, CodeStatsViewCache cache) {
        this.coding = coding; this.identities = identities; this.cache = cache;
    }

    void ensure(CodeStatsModule.ScopeInfo scope) {
        for (CodeStatsModule.MemberCredential member : scope.members()) {
            if (member.token() == null || member.token().isBlank()) continue;
            try {
            String key = CodingCredentialService.fingerprint(member.token());
            CodeStatsViewCache.CachedProfile profile = cache.profile(key).orElse(null);
            if (profile == null) {
                var user = coding.currentUserProfile(member.token());
                profile = new CodeStatsViewCache.CachedProfile(user.id(), user.name(), user.avatar(),
                        CodeStatsSupport.emailHash(user.email()), user.teamId(), System.currentTimeMillis());
                cache.putProfile(key, profile);
            }
            identities.initializeDefaults(scope.codingTeamId(), member.userId(), profile);
            } catch (RuntimeException unavailable) {
                if (scope.scope() == CodeStatsModels.Scope.SELF) throw unavailable;
                // 单成员 PAT 不可用不阻断其他成员；扫描记录覆盖不完整。
            }
        }
    }
}
