package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeIdentityMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Candidate;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.CreateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityMapping;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Page;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.UpdateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.User;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
class CodeIdentityService {

    private final ZhaogangCodeIdentityMapper mapper;
    private final ZhaogangCodeCommitMapper commitMapper;
    private final CodeStatsScopeDirectory directory;
    private final CodeIdentityResolver resolver;
    private final CodeStatsViewCache cache;
    private final CodeIdentityDefaults defaults;
    private final ObjectProvider<CodeBackfillTrigger> backfillTrigger;

    CodeIdentityService(ZhaogangCodeIdentityMapper mapper, ZhaogangCodeCommitMapper commitMapper,
                        CodeStatsScopeDirectory directory, CodeIdentityResolver resolver, CodeStatsViewCache cache,
                        ObjectProvider<CodeBackfillTrigger> backfillTrigger, CodeIdentityDefaults defaults) {
        this.mapper = mapper;
        this.commitMapper = commitMapper;
        this.directory = directory;
        this.resolver = resolver;
        this.cache = cache;
        this.backfillTrigger = backfillTrigger;
        this.defaults = defaults;
    }

    void ensureDefaults(CodeStatsModule.ScopeInfo scope) { defaults.ensure(scope); }

    String signature(CodeStatsModule.ScopeInfo scope) { return resolver.signature(scope); }

    int countMyIdentities(long codingTeamId, long userId) {
        return (int) mapper.selectByScope(codingTeamId, Scope.SELF.name(), userId).stream()
                .filter(item -> !Boolean.TRUE.equals(item.getIgnored()))
                .count();
    }

    IdentityPage identities(CodeStatsModule.Context context, String scopeValue, Long workbenchTeamId,
                            String keyword, int pageNumber, int pageSize) {
        Scope scope = parseScope(scopeValue);
        CodeStatsModule.ScopeInfo info = directory.resolve(context, scope, workbenchTeamId);
        defaults.ensure(info);
        List<IdentityEntity> mappings = resolver.mappings(info);
        Map<Long, CodeStatsModule.MemberCredential> members = new LinkedHashMap<>();
        info.members().forEach(member -> members.put(member.userId(), member));
        String normalizedKeyword = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        List<IdentityMapping> mappingViews = mappings.stream()
                .filter(item -> !CodeIdentityResolver.DELETED.equals(item.getSource()))
                .filter(item -> normalizedKeyword.isEmpty()
                        || StringUtils.defaultString(item.getAuthorName()).toLowerCase(Locale.ROOT)
                        .contains(normalizedKeyword))
                .sorted(Comparator.comparing(IdentityEntity::getId).reversed())
                .map(item -> toMapping(item, scope, members))
                .toList();

        List<Candidate> candidates = new ArrayList<>();
        int safeSize = Math.max(1, Math.min(100, pageSize));
        int safePage = Math.max(1, pageNumber);
        int from = Math.min(candidates.size(), (safePage - 1) * safeSize);
        int to = Math.min(candidates.size(), from + safeSize);
        return new IdentityPage(mappingViews, candidates.subList(from, to),
                new Page(safePage, safeSize, candidates.size(),
                        candidates.isEmpty() ? 0 : (candidates.size() + safeSize - 1) / safeSize));
    }

    IdentityMapping create(CodeStatsModule.Context context, CreateIdentityRequest request) {
        Scope scope = parseScope(request.scope());
        CodeStatsModule.ScopeInfo info = directory.resolve(context, scope, request.workbenchTeamId());
        boolean ignored = Boolean.TRUE.equals(request.ignored());
        if (scope == Scope.WORKBENCH_TEAM) {
            throw new CodeStatsForbiddenException("团队统一使用成员本人的 Git 身份，请在我的 Git 身份中维护");
        }
        if (StringUtils.isBlank(request.identityValue())) {
            throw new CodingOpenApiException("请输入需要绑定的邮箱或作者名");
        }
        String type = parseIdentityType(request.identityType());
        String key = CodeIdentityResolver.EMAIL.equals(type)
                ? CodeStatsSupport.emailHash(request.identityValue())
                : CodeStatsSupport.nameKey(request.identityValue());
        if (StringUtils.isBlank(key)) {
            throw new CodingOpenApiException("请输入需要绑定的邮箱或作者名");
        }
        IdentityEntity existing = mapper.selectOneByIdentity(info.codingTeamId(), scope.name(), info.scopeId(),
                type, key);
        IdentityEntity entity = existing == null ? new IdentityEntity() : existing;
        entity.setCodingTeamId(info.codingTeamId());
        entity.setScopeType(scope.name());
        entity.setScopeId(info.scopeId());
        entity.setIdentityType(type);
        entity.setIdentityKey(key);
        entity.setCodingUserId(context.userId());
        entity.setIgnored(false);
        entity.setAuthorName(CodeIdentityResolver.EMAIL.equals(type)
                ? CodeStatsSupport.maskEmail(request.identityValue())
                : CodeStatsSupport.maskName(request.identityValue()));
        entity.setSource(CodeIdentityResolver.SELF);
        entity.setUpdatedBy(context.userId());
        if (entity.getId() == null) mapper.insert(entity); else mapper.updateById(entity);
        afterMappingChanged(info, entity);
        return toMapping(entity, scope, memberMap(info));
    }

    IdentityMapping update(CodeStatsModule.Context context, long identityId, UpdateIdentityRequest request) {
        IdentityEntity entity = mapper.selectById(identityId);
        if (entity == null) throw new CodingOpenApiException("身份映射不存在");
        CodeStatsModule.ScopeInfo info = resolveScopeOf(context, entity);
        requireWritePermission(context, info);
        if (request.ignored() != null && request.ignored() && request.codingUserId() != null) {
            throw new CodingOpenApiException("忽略身份时不能同时指定成员");
        }
        if (request.codingUserId() == null && request.ignored() == null) {
            throw new CodingOpenApiException("请选择成员或忽略该身份");
        }
        if (request.codingUserId() != null && request.codingUserId() != context.userId()) {
            throw new CodeStatsForbiddenException("个人 Git 身份只能归属本人");
        }
        entity.setCodingUserId(context.userId());
        entity.setIgnored(Boolean.TRUE.equals(request.ignored()));
        entity.setSource(Scope.WORKBENCH_TEAM == info.scope()
                ? CodeIdentityResolver.MANUAL : CodeIdentityResolver.SELF);
        entity.setUpdatedBy(context.userId());
        mapper.updateById(entity);
        afterMappingChanged(info, entity);
        return toMapping(entity, info.scope(), memberMap(info));
    }

    void delete(CodeStatsModule.Context context, long identityId) {
        IdentityEntity entity = mapper.selectById(identityId);
        if (entity == null) throw new CodingOpenApiException("身份映射不存在");
        CodeStatsModule.ScopeInfo info = resolveScopeOf(context, entity);
        requireWritePermission(context, info);
        entity.setIgnored(true);
        entity.setSource(CodeIdentityResolver.DELETED);
        entity.setCodingUserId(context.userId());
        entity.setUpdatedBy(context.userId());
        mapper.updateById(entity);
        afterMappingChanged(info, entity);
    }

    private CodeStatsModule.ScopeInfo resolveScopeOf(CodeStatsModule.Context context, IdentityEntity entity) {
        Scope scope = parseScope(entity.getScopeType());
        if (scope == Scope.SELF && entity.getScopeId() != context.userId()) {
            throw new CodingOpenApiException("无权操作其他成员的个人代码身份");
        }
        return directory.resolve(context, scope, scope == Scope.WORKBENCH_TEAM ? entity.getScopeId() : null);
    }

    private void requireWritePermission(CodeStatsModule.Context context, CodeStatsModule.ScopeInfo info) {
        if (info.scope() == Scope.WORKBENCH_TEAM) {
            throw new CodeStatsForbiddenException("团队身份由成员本人维护");
        }
        if (info.scopeId() != context.userId()) {
            throw new CodeStatsForbiddenException("个人代码身份仅本人可修改");
        }
    }

    private void requireAdministrator(CodeStatsModule.ScopeInfo info) {
        if (!info.administrator()) {
            throw new CodeStatsForbiddenException("只有团队管理员可以维护团队代码身份映射");
        }
    }

    private void requireMember(CodeStatsModule.ScopeInfo info, Long codingUserId, boolean ignored) {
        if (ignored) return;
        if (codingUserId == null || codingUserId <= 0) {
            throw new CodingOpenApiException("请选择需要绑定的团队成员");
        }
        if (info.members().stream().noneMatch(member -> member.userId() == codingUserId)) {
            throw new CodingOpenApiException("绑定的成员不在当前工作台团队中");
        }
    }

    private void afterMappingChanged(CodeStatsModule.ScopeInfo info, IdentityEntity entity) {
        String scopeKey = cache.scopeKey(info);
        cache.bumpIdentityVersion(scopeKey);
        // 新身份需要异步回补；删除立即由查询排除。只刷新已经显式启用持续同步的范围。
        CodeBackfillTrigger trigger=backfillTrigger.getIfAvailable();
        if(trigger!=null) trigger.enqueueBackfill(info,java.time.YearMonth.now(CodeStatsSupport.CHINA_ZONE).toString(),entity.getUpdatedBy());
    }

    private IdentityMapping toMapping(IdentityEntity entity, Scope scope,
                                      Map<Long, CodeStatsModule.MemberCredential> members) {
        CodeStatsModule.MemberCredential member = entity.getCodingUserId() == null
                ? null : members.get(entity.getCodingUserId());
        User user = member == null ? null : new User(member.userId(), member.userName(), member.avatar());
        String displayValue = "";
        if (scope == Scope.SELF) {
            String authorName = StringUtils.defaultString(entity.getAuthorName());
            displayValue = CodeIdentityResolver.EMAIL.equals(entity.getIdentityType())
                    ? (authorName.contains("@") ? authorName : "已绑定邮箱")
                    : CodeStatsSupport.maskName(authorName);
        }
        return new IdentityMapping(entity.getId(), entity.getIdentityType(), displayValue,
                StringUtils.defaultString(entity.getAuthorName()), user,
                StringUtils.defaultString(entity.getSource()), Boolean.TRUE.equals(entity.getIgnored()),
                CodeStatsSupport.format(entity.getUpdateTime()));
    }

    private Map<Long, CodeStatsModule.MemberCredential> memberMap(CodeStatsModule.ScopeInfo info) {
        Map<Long, CodeStatsModule.MemberCredential> members = new LinkedHashMap<>();
        info.members().forEach(member -> members.put(member.userId(), member));
        return members;
    }

    private CodeStatsModule.MemberCredential suggestMember(CodeStatsModule.ScopeInfo info, String authorName) {
        String key = CodeStatsSupport.normalizeName(authorName);
        if (key.isBlank()) return null;
        List<CodeStatsModule.MemberCredential> matches = info.members().stream()
                .filter(member -> CodeStatsSupport.normalizeName(member.userName()).equals(key))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private Scope parseScope(String value) {
        try {
            return Scope.valueOf(StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CodingOpenApiException("代码统计范围只支持 SELF 或 WORKBENCH_TEAM");
        }
    }

    private String parseIdentityType(String value) {
        String type = StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
        if (!CodeIdentityResolver.EMAIL.equals(type) && !CodeIdentityResolver.NAME.equals(type)) {
            throw new CodingOpenApiException("身份类型只支持 EMAIL 或 NAME");
        }
        return type;
    }
}
