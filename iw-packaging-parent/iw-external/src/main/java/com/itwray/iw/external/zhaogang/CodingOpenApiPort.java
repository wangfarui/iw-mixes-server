package com.itwray.iw.external.zhaogang;

import java.math.BigDecimal;
import java.util.List;

/** CODING OpenAPI 的内部接口；业务模块不向 Controller 泄露其 Action 和原始字段。 */
public interface CodingOpenApiPort {

    CodingUser currentUser(String token);

    List<CodingProject> projects(String token, long userId);

    List<CodingPlan> plans(String token, long projectId);

    CodingPlan plan(String token, long projectId, long jobId);

    List<CodingBuild> builds(String token, long projectId, long jobId);

    CodingBuild latestBuild(String token, long projectId, long jobId);

    List<CodingBranch> branches(String token, long projectId, long depotId, String depotType, String keyword);

    CodingBuild triggerBuild(String token, long projectId, long jobId, String branch, String environment,
                             List<CodingBuildParameter> defaultParameters);

    Team team(String token);

    TeamDirectory teamDirectory(String token);

    WorklogPage worklogPage(String token, long startAt, long endAt, long userId, int offset, int limit);

    Issue issue(String token, String projectName, long issueCode);

    List<IssueType> issueTypes(String token, String projectName);

    List<IssueField> issueFields(String token, String projectName, String issueType, long issueTypeId);

    List<IssueStatus> issueStatuses(String token, String projectName, String issueType, long issueTypeId);

    List<IssueWorklog> issueWorklogs(String token, String projectName, long issueCode);

    Issue createIssue(String token, CreateIssueRequest request);

    Issue modifyIssue(String token, ModifyIssueRequest request);

    String createIssueWorkHours(String token, String projectName, long issueCode, BigDecimal spendHour,
                                BigDecimal remainingHour, long startAt);

    record IssueType(long id, String name, String systemType, boolean system, String splitType,
                     List<Long> splitTargetIssueTypeIds) {
        public IssueType(long id, String name, String systemType, boolean system) {
            this(id, name, systemType, system, "", List.of());
        }
        public IssueType {
            splitTargetIssueTypeIds = splitTargetIssueTypeIds == null ? List.of() : List.copyOf(splitTargetIssueTypeIds);
        }
    }

    record IssueFieldOption(String value, String title) {
    }

    record IssueField(long id, String name, String type, String componentType, boolean required,
                      boolean needDefault, String defaultValue, List<IssueFieldOption> options) {
        public IssueField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record CustomFieldValue(long id, String content) {
    }

    record IssueStatus(long id, String name, String type) {
    }

    record CreateIssueRequest(String projectName, String type, long issueTypeId, Long parentCode, String name,
                              String description, String priority, Long assigneeId, BigDecimal workingHours,
                              List<CustomFieldValue> customFieldValues) {
        public CreateIssueRequest {
            customFieldValues = customFieldValues == null ? List.of() : List.copyOf(customFieldValues);
        }

        public CreateIssueRequest(String projectName, String type, long issueTypeId, Long parentCode, String name,
                                  String description, String priority, BigDecimal workingHours,
                                  List<CustomFieldValue> customFieldValues) {
            this(projectName, type, issueTypeId, parentCode, name, description, priority, null, workingHours,
                    customFieldValues);
        }
    }

    record ModifyIssueRequest(String projectName, long issueCode, String name, String description, Long statusId,
                              String priority, BigDecimal workingHours, List<CustomFieldValue> customFieldValues) {
        public ModifyIssueRequest {
            customFieldValues = customFieldValues == null ? List.of() : List.copyOf(customFieldValues);
        }
    }

    record IssueWorklog(long id, BigDecimal spendHours, BigDecimal remainingHours, long startAt,
                        long createdAt, long updatedAt) {
    }

    record Team(long id, String name, String host) {
    }

    record Department(long id, String name, String path) {
    }

    record Member(long id, String name, String avatar, boolean active, List<Department> departments) {
    }

    record TeamDirectory(List<Member> members) {
    }

    record Worklog(long id, long issueId, long issueCode, String projectName, long userId,
                   BigDecimal hours, String workingDesc, Long startAt, Long createdAt, Long updatedAt) {
    }

    record WorklogPage(List<Worklog> items) {
    }

    record Issue(long code, String type, String typeName, long issueTypeId, String title,
                 String projectDisplayName, boolean subtask, long id, String statusName, String statusType,
                 BigDecimal workingHours, String description, Long parentCode, String parentProjectName,
                 String parentType, String parentTypeName, String developmentTeam, String definitionOfDone,
                 String taskType, String assigneeName) {

        public Issue(long code, String type, String typeName, long issueTypeId, String title,
                     String projectDisplayName, boolean subtask, long id, String statusName, String statusType,
                     BigDecimal workingHours) {
            this(code, type, typeName, issueTypeId, title, projectDisplayName, subtask, id, statusName, statusType,
                    workingHours, null, null, null, null, null, null, null, null, null);
        }

        public Issue(long code, String type, String typeName, long issueTypeId, String title,
                     String projectDisplayName, boolean subtask, long id, String statusName, String statusType,
                     BigDecimal workingHours, String description, Long parentCode, String parentProjectName,
                     String parentType, String parentTypeName, String developmentTeam, String definitionOfDone,
                     String taskType) {
            this(code, type, typeName, issueTypeId, title, projectDisplayName, subtask, id, statusName, statusType,
                    workingHours, description, parentCode, parentProjectName, parentType, parentTypeName,
                    developmentTeam, definitionOfDone, taskType, null);
        }

        public Issue(long code, String type, String typeName, String title, String projectDisplayName,
                     boolean subtask, long id, String statusName, String statusType) {
            this(code, type, typeName, 0, title, projectDisplayName, subtask, id, statusName, statusType,
                    BigDecimal.ZERO);
        }

        public Issue(long code, String type, String typeName, String title, String projectDisplayName, boolean subtask) {
            this(code, type, typeName, 0, title, projectDisplayName, subtask, code, "", "", BigDecimal.ZERO);
        }

        public Issue {
            workingHours = workingHours == null ? BigDecimal.ZERO : workingHours;
        }
    }
}

record CodingUser(long id, String name, String avatar, String globalKey, long teamId) {
}

record CodingProject(long id, String name, String displayName) {
}

record CodingPlan(long id, String name, long projectId, Long depotId, String depotType, String defaultBranch,
                  List<String> environments, boolean quickBuildSupported, List<CodingBuildParameter> defaultParameters,
                  CodingBuild latestBuild) {
}

record CodingBuildParameter(String name, String value, boolean sensitive) {
}

record CodingBuild(long id, String number, String status, String statusDetail, String branch, String commit,
                   String triggerUser,
                   String duration, String startedAt) {
}

record CodingBranch(String name) {
}
