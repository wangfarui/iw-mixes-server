package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.CreateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.EntryPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Failures;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityMapping;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.IdentityPage;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.MemberStatistics;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Options;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanState;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.ScanTrigger;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.UpdateIdentityRequest;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.CodingRole;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamModels.TeamStatisticsCredential;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public interface CodeStatsModule {

    Options options(Context context);

    Object statistics(Context context, String from, String to, String scope, Long workbenchTeamId, boolean refresh);

    MemberStatistics memberStatistics(Context context, String from, String to, long workbenchTeamId,
                                      long memberUserId);

    EntryPage entries(Context context, String from, String to, String scope, Long workbenchTeamId,
                      Long memberUserId, Long depotId, String keyword, int pageNumber, int pageSize,
                      CodeStatsModels.EntrySort sortBy, CodeStatsModels.SortDirection sortOrder, boolean refresh);

    ScanState scan(Context context, String from, String to, String scope, Long workbenchTeamId);

    ScanTrigger triggerScan(Context context, ScanRequest request);

    ScanState cancel(Context context, long jobId);

    Failures failures(Context context, long jobId, int pageNumber, int pageSize);

    CodeStatsModels.DiffRetryJob retryDiffs(Context context, long sourceJobId, CodeStatsModels.DiffRetryRequest request);

    CodeStatsModels.DiffRetryJob diffRetryStatus(Context context, long jobId);

    IdentityPage identities(Context context, String scope, Long workbenchTeamId, String keyword, int pageNumber,
                            int pageSize);

    IdentityMapping createIdentity(Context context, CreateIdentityRequest request);

    IdentityMapping updateIdentity(Context context, long identityId, UpdateIdentityRequest request);

    void deleteIdentity(Context context, long identityId);

    record Context(String token, long userId, String userName, String avatar, long codingTeamId,
                   String codingTeamKey, String codingTeamHost) {
    }

    record MemberCredential(long userId, String userName, String avatar, String token, CodingRole codingRole,
                            boolean managedStatistics, boolean ignoreTeamStatistics) {
        public MemberCredential(long userId, String userName, String avatar, String token) {
            this(userId, userName, avatar, token, null);
        }

        public MemberCredential(long userId, String userName, String avatar, String token, CodingRole codingRole) {
            this(userId, userName, avatar, token, codingRole, false, false);
        }

        @Override
        public String toString() {
            return "MemberCredential[userId=" + userId + ", managedStatistics=" + managedStatistics + "]";
        }
    }

    record ScopeInfo(Scope scope, long scopeId, long codingTeamId, String teamKey, String teamHost,
                     String teamName, List<MemberCredential> members, boolean administrator,
                     boolean automaticSyncEligible, TeamStatisticsCredential managementCredential) {
        public ScopeInfo(Scope scope, long scopeId, long codingTeamId, String teamKey, String teamHost,
                         String teamName, List<MemberCredential> members, boolean administrator, boolean automaticSyncEligible) {
            this(scope, scopeId, codingTeamId, teamKey, teamHost, teamName, members, administrator,
                    automaticSyncEligible, TeamStatisticsCredential.EMPTY);
        }

        public ScopeInfo(Scope scope, long scopeId, long codingTeamId, String teamKey, String teamHost,
                         String teamName, List<MemberCredential> members, boolean administrator) {
            this(scope, scopeId, codingTeamId, teamKey, teamHost, teamName, members, administrator, true);
        }

        public ScopeInfo {
            members = members == null ? List.of() : List.copyOf(members);
        }

        String repositoryToken(MemberCredential member) {
            return member.managedStatistics() ? managementCredential.token() : member.token();
        }

        boolean hasRepositoryToken(MemberCredential member) {
            return StringUtils.isNotBlank(repositoryToken(member));
        }

        List<MemberCredential> developmentMembers() {
            return members.stream()
                    .filter(member -> !member.ignoreTeamStatistics())
                    .filter(member -> member.codingRole() == CodingRole.BACKEND || member.codingRole() == CodingRole.FRONTEND)
                    .toList();
        }
    }
}
