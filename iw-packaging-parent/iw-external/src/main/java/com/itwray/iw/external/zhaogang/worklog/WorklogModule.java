package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.RangeReport;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueCheckStatus;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.EntriesPage;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;

public interface WorklogModule {

    Options options(Context context);

    RangeReport report(Context context, String from, String to, String scope,
                                     Long workbenchTeamId, boolean refresh);

    Statistics statistics(Context context, String month, String scope, Long workbenchTeamId, boolean refresh);

    Entries entries(Context context, String from, String to, String scope, Long workbenchTeamId, boolean refresh);

    EntriesPage entriesPage(Context context, String from, String to, String scope, Long workbenchTeamId,
                            Long memberUserId, String cursor, boolean refresh);

    Absence absences(Context context, String month, String scope, Long workbenchTeamId, boolean refresh);

    IssueCheckStatus issueCheck(Context context, String scope, Long workbenchTeamId, String checkId);

    record Context(String token, long userId, String userName, String avatar, long codingTeamId,
                   String codingTeamKey, String codingTeamHost) {
    }

    /** Internal member credential; never serialized into a Web response. */
    record MemberCredential(long userId, String userName, String avatar, String token, boolean managedStatistics) {
        public MemberCredential(long userId, String userName, String avatar, String token) {
            this(userId, userName, avatar, token, false);
        }

        @Override
        public String toString() {
            return "MemberCredential[userId=" + userId + ", managedStatistics=" + managedStatistics + "]";
        }
    }
}
