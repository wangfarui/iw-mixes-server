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

import java.util.List;

public interface CodeStatsModule {

    Options options(Context context);

    Object statistics(Context context, String from, String to, String scope, Long workbenchTeamId, boolean refresh);

    MemberStatistics memberStatistics(Context context, String from, String to, long workbenchTeamId,
                                      long memberUserId);

    EntryPage entries(Context context, String from, String to, Long depotId, String keyword, int pageNumber,
                      int pageSize, boolean refresh);

    ScanState scan(Context context, String from, String to, String scope, Long workbenchTeamId);

    ScanTrigger triggerScan(Context context, ScanRequest request);

    ScanState cancel(Context context, long jobId);

    Failures failures(Context context, long jobId, int pageNumber, int pageSize);

    IdentityPage identities(Context context, String scope, Long workbenchTeamId, String keyword, int pageNumber,
                            int pageSize);

    IdentityMapping createIdentity(Context context, CreateIdentityRequest request);

    IdentityMapping updateIdentity(Context context, long identityId, UpdateIdentityRequest request);

    void deleteIdentity(Context context, long identityId);

    record Context(String token, long userId, String userName, String avatar, long codingTeamId,
                   String codingTeamKey, String codingTeamHost) {
    }

    record MemberCredential(long userId, String userName, String avatar, String token) {
    }

    record ScopeInfo(Scope scope, long scopeId, long codingTeamId, String teamKey, String teamHost,
                     String teamName, List<MemberCredential> members, boolean administrator) {
        public ScopeInfo {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
