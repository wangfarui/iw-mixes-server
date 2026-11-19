package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.profile.WorkbenchProfile.RolePreference;

import java.util.List;
import java.util.Map;

/** 对 Web 保持稳定的 Dev Hub 返回模型。 */
public final class ZhaogangModels {

    private ZhaogangModels() {
    }

    public record SessionStatus(boolean connected, Long userId, String userName, String avatar, String team,
                                String tokenHint, boolean tokenRotationRequired, List<String> warnings,
                                RolePreference rolePreference) {
    }

    public record TokenValue(String token) {
    }

    public record K8sTokenStatus(List<String> environments, Map<String, Boolean> configured,
                                 Map<String, K8sTokenDisplay> credentials) {
    }

    public enum K8sTokenSource { PERSONAL, DEFAULT, NONE }

    public record K8sTokenDisplay(K8sTokenSource source, String maskedToken, String revision, boolean defaultAvailable) {
    }

    public record K8sTokenValue(String token, String revision) {
        @Override
        public String toString() {
            return "K8sTokenValue[revision=" + revision + "]";
        }
    }

    public record Project(long id, String name, String displayName) {
    }

    public record PlanCatalog(List<Project> projects, List<Plan> plans, List<Long> failedProjectIds,
                              String lastSyncedAt, boolean refreshing) {
    }

    public record PlanPageSync(List<Plan> plans, List<Long> failedProjectIds, String lastSyncedAt) {
    }

    public record Plan(long id, long projectId, String projectName, String projectDisplayName, String name,
                       String defaultBranch, List<String> environments, boolean quickBuildSupported,
                       Build latestBuild) {
    }

    public record Build(long id, String number, String status, String statusDetail, String branch, String commit,
                        String triggerUser, String duration, String startedAt, String environment) {

        public Build(long id, String number, String status, String statusDetail, String branch, String commit,
                     String triggerUser, String duration, String startedAt) {
            this(id, number, status, statusDetail, branch, commit, triggerUser, duration, startedAt, "");
        }
    }

    public record PlanDetail(Plan plan, List<Build> builds, Long depotId) {
    }

    public record Branch(String name) {
    }

}
