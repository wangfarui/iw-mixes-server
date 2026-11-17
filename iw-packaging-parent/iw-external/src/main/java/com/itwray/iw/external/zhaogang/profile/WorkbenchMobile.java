package com.itwray.iw.external.zhaogang.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itwray.iw.external.zhaogang.team.WorkbenchTeamException;

/** Shared by self-service profiles, team administration and notification recipients. */
public final class WorkbenchMobile {
    private WorkbenchMobile() { }
    public record Preference(String mobile) { }
    public record Update(@JsonProperty(required = true) String mobile) {
        @Override public String toString() { return "Update[mobile=[redacted]]"; }
    }
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String mobile = value.trim();
        if (!mobile.matches("1[3-9][0-9]{9}"))
            throw new WorkbenchTeamException("请输入有效的11位手机号，或留空清除");
        return mobile;
    }
}
