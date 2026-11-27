package com.itwray.iw.external.zhaogang.credential;

/** Published after the credential changes. Listeners resolve the current credential from storage. */
public record StatisticsCredentialChanged(long organization, long userId, Long workbenchTeamId) {}
