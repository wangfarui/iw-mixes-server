package com.itwray.iw.external.zhaogang.codestats;

/** 身份映射变更后触发历史提交行数补齐；由扫描服务实现。 */
interface CodeBackfillTrigger {

    void enqueueBackfill(CodeStatsModule.ScopeInfo scope, String month, long requestedBy);
}
