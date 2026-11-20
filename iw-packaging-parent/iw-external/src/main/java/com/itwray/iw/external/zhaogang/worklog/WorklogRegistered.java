package com.itwray.iw.external.zhaogang.worklog;

/** A successful remote registration, not a second source of worklog facts. */
public record WorklogRegistered(long codingTeamId, long codingUserId) {}
