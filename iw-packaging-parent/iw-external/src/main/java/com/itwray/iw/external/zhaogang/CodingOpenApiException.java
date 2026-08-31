package com.itwray.iw.external.zhaogang;

import java.util.List;
import java.util.Map;

public class CodingOpenApiException extends RuntimeException {

    private static final Map<String, List<String>> ACTION_PERMISSIONS = Map.ofEntries(
            Map.entry("DescribeCodingCurrentUser", List.of("用户信息（只读）")),
            Map.entry("DescribeTeam", List.of("团队信息（只读）")),
            Map.entry("DescribeTeamMembers", List.of("团队信息（只读）", "团队成员（只读）")),
            Map.entry("DescribeUserProjects", List.of("项目信息（只读）")),
            Map.entry("DescribeCodingCIJobs", List.of("持续集成任务（只读）")),
            Map.entry("DescribeCodingCIJob", List.of("持续集成任务（只读）")),
            Map.entry("DescribeCodingCIBuilds", List.of("持续集成构建（读写）")),
            Map.entry("DescribeProjectDepotBranches", List.of("代码仓库（只读）")),
            Map.entry("TriggerCodingCIBuild", List.of("持续集成构建（读写）")),
            Map.entry("DescribeAllProjectsIssueWorkLogList", List.of("项目协同（读写）")),
            Map.entry("DescribeIssue", List.of("项目协同（读写）")),
            Map.entry("DescribeProjectIssueTypeList", List.of("项目协同（读写）")),
            Map.entry("DescribeProjectIssueFieldList", List.of("项目协同（读写）")),
            Map.entry("DescribeProjectIssueStatusList", List.of("项目协同（读写）")),
            Map.entry("DescribeIssueWorkLogList", List.of("项目协同（读写）")),
            Map.entry("CreateIssue", List.of("项目协同（读写）")),
            Map.entry("ModifyIssue", List.of("项目协同（读写）")),
            Map.entry("CreateIssueWorkHours", List.of("项目协同（读写）"))
    );

    private final String action;

    private final String code;

    public CodingOpenApiException(String message) {
        this("", "", message);
    }

    public CodingOpenApiException(String code, String message) {
        this("", code, message);
    }

    public CodingOpenApiException(String action, String code, String message) {
        super(message);
        this.action = action == null ? "" : action;
        this.code = code == null ? "" : code;
    }

    public CodingOpenApiException(String message, Throwable cause) {
        super(message, cause);
        this.action = "";
        this.code = "";
    }

    public String action() {
        return action;
    }

    public String code() {
        return code;
    }

    public boolean isPermissionDenied() {
        String normalized = (code + " " + getMessage()).toLowerCase();
        return normalized.contains("unauthorized") || normalized.contains("forbidden")
                || normalized.contains("http_401") || normalized.contains("http_403")
                || normalized.contains("permission") || normalized.contains("无权")
                || normalized.contains("权限");
    }

    public boolean isTransportFailure() {
        return getCause() != null;
    }

    public List<String> requiredPermissions() {
        return ACTION_PERMISSIONS.getOrDefault(action, List.of());
    }

    public String permissionMessage() {
        List<String> permissions = requiredPermissions();
        if (permissions.isEmpty()) {
            return "CODING 已拒绝本次调用。请检查个人令牌权限，并确认当前账号拥有对应资源的访问权限";
        }
        return "当前 CODING 令牌缺少“" + String.join("、", permissions)
                + "”权限。请前往 CODING 令牌管理开通后重试；若已开通，请联系团队管理员检查账号权限";
    }
}
