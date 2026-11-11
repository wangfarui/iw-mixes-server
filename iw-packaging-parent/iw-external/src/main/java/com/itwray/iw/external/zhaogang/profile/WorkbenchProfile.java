package com.itwray.iw.external.zhaogang.profile;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public final class WorkbenchProfile {

    private WorkbenchProfile() {
    }

    public enum CodingRole {
        PRODUCT, BACKEND, FRONTEND, QA
    }

    public record RolePreference(CodingRole codingRole, boolean roleConfigured) {
        public static final RolePreference UNSET = new RolePreference(null, false);
    }

    public record UpdateRole(@JsonProperty(required = true) CodingRole codingRole, boolean initializeOnly) {
    }

    public record MemberProfile(long userId, CodingRole codingRole, LocalDateTime lastUsedAt) {
    }
}
