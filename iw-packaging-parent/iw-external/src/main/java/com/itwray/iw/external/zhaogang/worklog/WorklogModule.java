package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Options;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Entries;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Absence;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.Statistics;

public interface WorklogModule {

    Options options(Context context);

    Statistics statistics(Context context, String month, String scope, Long workbenchTeamId, boolean refresh);

    Entries entries(Context context, String from, String to, String scope, Long workbenchTeamId, boolean refresh);

    Absence absences(Context context, String month, String scope, Long workbenchTeamId, boolean refresh);

    record Context(String token, long userId, String userName, String avatar, long codingTeamId,
                   String codingTeamKey, String codingTeamHost) {
    }

    /** Internal member credential; never serialized into a Web response. */
    record MemberCredential(long userId, String userName, String avatar, String token) {
    }
}
