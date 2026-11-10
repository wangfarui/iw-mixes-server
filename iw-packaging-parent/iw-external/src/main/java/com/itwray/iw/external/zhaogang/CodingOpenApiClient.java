package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommit;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitDiff;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitQuery;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingDepot;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingDepotPage;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingUserProfile;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Department;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Team;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.TeamDirectory;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Worklog;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.WorklogPage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CODING OpenAPI 的 HTTP adapter。只记录调用结果，不输出令牌和原始响应。
 */
@Component
class CodingOpenApiClient implements CodingOpenApiPort {

    private static final Logger log = LoggerFactory.getLogger(CodingOpenApiClient.class);

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final ZhaogangProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final CodingRequestLimiter requestLimiter;

    CodingOpenApiClient(ZhaogangProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new CodingRequestLimiter(properties));
    }

    @Autowired
    CodingOpenApiClient(ZhaogangProperties properties, ObjectMapper objectMapper,
                        CodingRequestLimiter requestLimiter) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requestLimiter = requestLimiter;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .build();
    }

    @Override
    public CodingUser currentUser(String token) {
        JsonNode response = invoke(token, "DescribeCodingCurrentUser", Collections.emptyMap());
        JsonNode user = response.path("User");
        if (user.isMissingNode() || user.isEmpty()) {
            throw new CodingOpenApiException("CODING 未返回当前用户信息");
        }
        return new CodingUser(longValue(user, "Id", "UserId"), text(user, "Name", "GlobalKey", "Login"),
                text(user, "Avatar", "AvatarUrl"), text(user, "GlobalKey", "Login"),
                longValue(user, "TeamId"));
    }

    @Override
    public CodingUserProfile currentUserProfile(String token) {
        JsonNode response = invoke(token, "DescribeCodingCurrentUser", Collections.emptyMap());
        JsonNode user = response.path("User");
        if (user.isMissingNode() || user.isEmpty()) {
            throw new CodingOpenApiException("CODING 未返回当前用户信息");
        }
        return new CodingUserProfile(longValue(user, "Id", "UserId"), text(user, "Name", "GlobalKey", "Login"),
                text(user, "Avatar", "AvatarUrl"), text(user, "Email", "EmailAddress"), longValue(user, "TeamId"));
    }

    @Override
    public CodingDepotPage myDepots(String token, int pageNumber, int pageSize) {
        JsonNode response = invoke(token, "DescribeMyDepots", Map.of(
                "PageNumber", pageNumber, "PageSize", pageSize));
        JsonNode data = response.path("Payload").isObject() ? response.path("Payload")
                : response.path("Data").isObject() ? response.path("Data") : response;
        List<JsonNode> items = array(data, "DepotList", "Depots", "DepotInfoList");
        if (!data.path("DepotList").isArray() && !data.path("Depots").isArray()
                && !data.path("DepotInfoList").isArray()) {
            throw new CodingOpenApiException("CODING 仓库列表响应结构不符合预期，请检查接口兼容性");
        }
        List<CodingDepot> depots = new ArrayList<>();
        for (JsonNode item : items) {
            long id = longValue(item, "Id", "DepotId");
            if (id <= 0) continue;
            String name = text(item, "Name", "DepotName");
            String projectName = text(item, "ProjectName", "ProjectPath");
            depots.add(new CodingDepot(id, longValue(item, "ProjectId"), projectName,
                    StringUtils.defaultIfBlank(text(item, "ProjectDisplayName", "ProjectName"), projectName),
                    name, text(item, "WebUrl", "HttpsUrl", "WebURL", "HttpsCloneUrl"),
                    text(item, "VcsType", "VCS"), StringUtils.defaultIfBlank(text(item, "DepotType"), "CODING")));
        }
        long total = longValue(data, "TotalCount", "Total", "TotalRow");
        if (total <= 0) total = longValue(data.path("Page"), "TotalRow", "TotalCount");
        if (total <= 0) total = longValue(response.path("Page"), "TotalRow", "TotalCount");
        return new CodingDepotPage(depots, total, pageNumber, pageSize);
    }

    @Override
    public CodingCommitPage commits(String token, CodingCommitQuery query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("DepotId", query.depotId());
        if (StringUtils.isNotBlank(query.ref())) request.put("Ref", query.ref());
        if (StringUtils.isNotBlank(query.startDate())) request.put("StartDate", query.startDate());
        if (StringUtils.isNotBlank(query.endDate())) request.put("EndDate", query.endDate());
        request.put("PageNumber", query.pageNumber());
        request.put("PageSize", query.pageSize());
        JsonNode response = invoke(token, "DescribeGitCommitsInPage", request);
        JsonNode data = response.path("Data").isObject() ? response.path("Data") : response;
        List<JsonNode> items = array(data, "Commits", "CommitList", "GitCommits");
        if (items.isEmpty()) items = array(response, "Commits", "CommitList", "GitCommits");
        boolean recognized = java.util.stream.Stream.of(data, response).anyMatch(node ->
                java.util.stream.Stream.of("Commits", "CommitList", "GitCommits")
                        .anyMatch(field -> node.has(field) && (node.path(field).isArray() || node.path(field).isNull())));
        if (!recognized) throw new CodingOpenApiException("CODING 提交列表响应结构不符合预期");
        List<CodingCommit> commits = new ArrayList<>();
        for (JsonNode item : items) {
            String sha = text(item, "Sha", "CommitSha", "Id");
            if (StringUtils.isBlank(sha)) continue;
            String message = StringUtils.defaultIfBlank(text(item, "ShortMessage", "FullMessage", "Title", "Message"), "");
            int lineBreak = message.indexOf('\n');
            if (lineBreak >= 0) message = message.substring(0, lineBreak);
            if (message.length() > 1024) message = message.substring(0, 1024);
            Long millis = epochMillis(item, "CommitDate", "CommittedAt", "CreatedAt", "AuthorDate");
            commits.add(new CodingCommit(sha, StringUtils.defaultIfBlank(text(item, "ShortSha"), abbreviateCommit(sha)),
                    text(item, "AuthorName", "CommitterName", "Author"), text(item, "AuthorEmail", "CommitterEmail"),
                    millis == null ? 0L : millis, message, parents(item)));
        }
        long total = longValue(data, "TotalCount", "Total", "TotalRow");
        if (total <= 0) total = longValue(data.path("Page"), "TotalRow", "TotalCount");
        if (total <= 0) total = longValue(response.path("Page"), "TotalRow", "TotalCount");
        return new CodingCommitPage(commits, total, query.pageNumber(), query.pageSize());
    }

    @Override
    public CodingCommitDiff commitDiff(String token, long projectId, long depotId, String sha) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("DepotId", depotId);
        request.put("Sha", sha);
        JsonNode response = invoke(token, "DescribeGitCommitDiff", request,
                Math.max(1, properties.getCodeDiffMaxBytes()));
        JsonNode data = response.path("Data").isObject() ? response.path("Data") : response;
        if (!hasDiffArray(data) && !hasDiffArray(response)) {
            throw new CodingOpenApiException("DescribeGitCommitDiff", "InvalidDiffResponse", "CODING 未返回 diff 列表");
        }
        List<JsonNode> diffs = array(data, "Diffs", "DiffList", "Files");
        if (diffs.isEmpty()) diffs = array(response, "Diffs", "DiffList", "Files");
        int additions = 0;
        int deletions = 0;
        for (JsonNode diff : diffs) {
            additions += intValue(diff, "Insertions", "Additions", "Added");
            deletions += intValue(diff, "Deletions", "Deleted");
        }
        return new CodingCommitDiff(additions, deletions, diffs.size());
    }

    private boolean hasDiffArray(JsonNode node) {
        return node.path("Diffs").isArray() || node.path("DiffList").isArray() || node.path("Files").isArray();
    }

    @Override
    public List<CodingProject> projects(String token, long userId) {
        if (userId <= 0) {
            throw new CodingOpenApiException("CODING 用户信息不完整");
        }
        JsonNode response = invoke(token, "DescribeUserProjects", Map.of("UserId", userId));
        List<CodingProject> result = new ArrayList<>();
        for (JsonNode item : array(response, "Projects", "ProjectList", "ProjectSet")) {
            long id = longValue(item, "Id", "ProjectId");
            if (id <= 0) {
                continue;
            }
            String name = text(item, "Name", "ProjectName", "NamePinYin");
            result.add(new CodingProject(id, name, StringUtils.defaultIfBlank(text(item, "DisplayName", "Name"), name)));
        }
        return result;
    }

    @Override
    public List<CodingPlan> plans(String token, long projectId) {
        JsonNode response = invoke(token, "DescribeCodingCIJobs", Map.of("ProjectId", projectId));
        List<CodingPlan> result = new ArrayList<>();
        for (JsonNode item : array(response, "Jobs", "CIJobs", "JobList")) {
            CodingPlan plan = toPlan(item, projectId);
            if (plan.id() > 0) {
                result.add(plan);
            }
        }
        return result;
    }

    @Override
    public CodingPlan plan(String token, long projectId, long jobId) {
        JsonNode response = invoke(token, "DescribeCodingCIJob", Map.of("JobId", jobId));
        JsonNode job = response.has("Job") ? response.path("Job") : response;
        CodingPlan plan = toPlan(job, projectId);
        if (plan.id() <= 0) {
            throw new CodingOpenApiException("未找到对应构建计划");
        }
        return plan;
    }

    @Override
    public List<CodingBuild> builds(String token, long projectId, long jobId) {
        return builds(token, jobId, 20);
    }

    @Override
    public CodingBuild latestBuild(String token, long projectId, long jobId) {
        List<CodingBuild> builds = builds(token, jobId, 1);
        return builds.isEmpty() ? null : builds.get(0);
    }

    private List<CodingBuild> builds(String token, long jobId, int pageSize) {
        JsonNode response = invoke(token, "DescribeCodingCIBuilds", Map.of(
                "JobId", jobId, "PageNumber", 1, "PageSize", pageSize));
        List<CodingBuild> result = new ArrayList<>();
        List<JsonNode> buildList = array(response.path("Data"), "BuildList");
        if (buildList.isEmpty()) {
            buildList = array(response, "Builds", "BuildList", "CIBuilds");
        }
        for (JsonNode item : buildList) {
            result.add(toBuild(item));
        }
        return result;
    }

    @Override
    public List<CodingBranch> branches(String token, long projectId, long depotId, String depotType, String keyword) {
        if (depotId <= 0) {
            return Collections.emptyList();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ProjectId", projectId);
        request.put("Id", depotId);
        request.put("DepotType", StringUtils.defaultIfBlank(depotType, "CODING"));
        JsonNode response = invoke(token, "DescribeProjectDepotBranches", request);
        List<CodingBranch> result = new ArrayList<>();
        List<JsonNode> branchList = array(response.path("Data"), "DepotDetailList");
        if (branchList.isEmpty()) {
            branchList = array(response, "Branches", "BranchList", "GitBranches");
        }
        String normalizedKeyword = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        for (JsonNode item : branchList) {
            String name = item.isTextual() ? item.asText() : text(item, "Name", "Branch", "DisplayName");
            if (StringUtils.isNotBlank(name) && (normalizedKeyword.isEmpty()
                    || name.toLowerCase(Locale.ROOT).contains(normalizedKeyword))) {
                result.add(new CodingBranch(name, text(item, "Sha", "CommitSha"), epochMillis(item, "LastCommitDate")));
            }
        }
        return result;
    }

    @Override
    public CodingBuild triggerBuild(String token, long projectId, long jobId, String branch, String environment,
                                    List<CodingBuildParameter> defaultParameters) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("JobId", jobId);
        request.put("Revision", branch);
        request.put("ParamList", triggerParameters(defaultParameters, environment));
        JsonNode response = invoke(token, "TriggerCodingCIBuild", request);
        JsonNode build = response.path("Data").path("Build");
        if (build.isMissingNode()) {
            build = response.has("Build") ? response.path("Build") : response;
        }
        CodingBuild parsed = toBuild(build);
        if (parsed == null) {
            return new CodingBuild(0L, "", "", "", branch, "", "", "", "", environment);
        }
        return new CodingBuild(parsed.id(), parsed.number(), parsed.status(), parsed.statusDetail(), branch,
                parsed.commit(), parsed.triggerUser(), parsed.duration(), parsed.startedAt(),
                StringUtils.defaultIfBlank(parsed.environment(), environment));
    }

    @Override
    public Team team(String token) {
        JsonNode response = invoke(token, "DescribeTeam", Collections.emptyMap());
        JsonNode team = firstObject(response, "Data", "Team");
        if (team.has("Team") && team.path("Team").isObject()) {
            team = team.path("Team");
        }
        long id = longValue(team, "Id", "TeamId");
        String host = text(team, "TeamHost", "Host");
        if (id <= 0 || StringUtils.isBlank(host)) {
            throw new CodingOpenApiException("CODING 未返回完整团队信息");
        }
        return new Team(id, text(team, "Name", "TeamName"), normalizeTeamHost(host));
    }

    @Override
    public TeamDirectory teamDirectory(String token) {
        List<CodingOpenApiPort.Member> result = new ArrayList<>();
        int pageNumber = 1;
        int pageSize = 100;
        long totalCount;
        do {
            JsonNode response = invoke(token, "DescribeTeamMembers", Map.of(
                    "PageNumber", pageNumber,
                    "PageSize", pageSize,
                    "ShowDepartment", true));
            JsonNode data = response.path("Data").isObject() ? response.path("Data") : response;
            List<JsonNode> items = array(data, "TeamMembers", "Members", "MemberList");
            items.stream().map(this::toMember).filter(member -> member.id() > 0).forEach(result::add);
            totalCount = longValue(data, "TotalCount", "Total", "Count");
            if (items.size() < pageSize || (totalCount > 0 && result.size() >= totalCount)) {
                break;
            }
            pageNumber++;
        } while (pageNumber <= 100);
        return new TeamDirectory(deduplicateMembers(result));
    }

    @Override
    public WorklogPage worklogPage(String token, long startAt, long endAt, long userId, int offset, int limit) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("StartAt", startAt);
        request.put("EndAt", endAt);
        request.put("UserId", userId);
        request.put("Offset", offset);
        request.put("Limit", limit);
        JsonNode response = invoke(token, "DescribeAllProjectsIssueWorkLogList", request);
        List<Worklog> result = new ArrayList<>();
        for (JsonNode item : array(response, "WorkLogs", "WorkLogList", "IssueWorkLogs")) {
            result.add(new Worklog(longValue(item, "Id", "WorkLogId"), longValue(item, "IssueId"),
                    longValue(item, "IssueCode", "IssueNumber"), text(item, "ProjectName"),
                    longValue(item, "UserId"), decimal(item, "RecordHours"), text(item, "WorkingDesc"),
                    epochMillis(item, "StartAt"), epochMillis(item, "CreatedAt"), epochMillis(item, "UpdatedAt")));
        }
        return new WorklogPage(result);
    }

    @Override
    public CodingOpenApiPort.Issue issue(String token, String projectName, long issueCode) {
        JsonNode response = invoke(token, "DescribeIssue", Map.of(
                "ProjectName", projectName,
                "IssueCode", issueCode));
        return toIssue(firstObject(response, "Issue", "Data"));
    }

    @Override
    public List<CodingOpenApiPort.IssueType> issueTypes(String token, String projectName) {
        JsonNode response = invoke(token, "DescribeProjectIssueTypeList", Map.of("ProjectName", projectName));
        List<CodingOpenApiPort.IssueType> result = new ArrayList<>();
        for (JsonNode item : array(response, "IssueTypes", "IssueTypeList")) {
            List<Long> splitTargets = new ArrayList<>();
            JsonNode targetIds = item.path("SplitTargetIssueTypeId");
            if (targetIds.isArray()) targetIds.forEach(target -> splitTargets.add(target.asLong()));
            result.add(new CodingOpenApiPort.IssueType(longValue(item, "Id", "IssueTypeId"),
                    text(item, "Name", "TypeName"), text(item, "IssueType", "Type"),
                    item.path("IsSystem").asBoolean(false), text(item, "SplitType"), splitTargets));
        }
        return result;
    }

    @Override
    public List<CodingOpenApiPort.IssueField> issueFields(String token, String projectName, String issueType,
                                                           long issueTypeId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ProjectName", projectName);
        request.put("IssueType", issueType);
        if (issueTypeId > 0) request.put("IssueTypeId", issueTypeId);
        JsonNode response = invoke(token, "DescribeProjectIssueFieldList", request);
        List<CodingOpenApiPort.IssueField> result = new ArrayList<>();
        for (JsonNode item : array(response, "ProjectIssueFieldList", "IssueFields")) {
            JsonNode field = item.path("IssueField").isObject() ? item.path("IssueField") : item;
            List<CodingOpenApiPort.IssueFieldOption> options = new ArrayList<>();
            JsonNode optionNodes = field.path("Options");
            if (optionNodes.isArray()) {
                optionNodes.forEach(option -> options.add(new CodingOpenApiPort.IssueFieldOption(
                        text(option, "Value"), text(option, "Title", "Name"))));
            }
            result.add(new CodingOpenApiPort.IssueField(longValue(item, "IssueFieldId", "Id"),
                    text(field, "Name"), text(field, "Type"), text(field, "ComponentType"),
                    item.path("Required").asBoolean(field.path("Required").asBoolean(false)),
                    item.path("NeedDefault").asBoolean(false), text(item, "ValueString"), options));
        }
        return result;
    }

    @Override
    public List<CodingOpenApiPort.IssueStatus> issueStatuses(String token, String projectName, String issueType,
                                                              long issueTypeId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ProjectName", projectName);
        request.put("IssueType", issueType);
        if (issueTypeId > 0) request.put("IssueTypeId", issueTypeId);
        JsonNode response = invoke(token, "DescribeProjectIssueStatusList", request);
        List<CodingOpenApiPort.IssueStatus> result = new ArrayList<>();
        for (JsonNode item : array(response, "ProjectIssueStatusList")) {
            JsonNode status = item.path("IssueStatus");
            result.add(new CodingOpenApiPort.IssueStatus(longValue(item, "IssueStatusId", "Id"),
                    text(status, "Name", "DisplayName"), text(status, "Type", "StatusType")));
        }
        return result;
    }

    @Override
    public List<CodingOpenApiPort.IssueWorklog> issueWorklogs(String token, String projectName, long issueCode) {
        JsonNode response = invoke(token, "DescribeIssueWorkLogList", Map.of(
                "ProjectName", projectName,
                "IssueCode", issueCode));
        List<CodingOpenApiPort.IssueWorklog> result = new ArrayList<>();
        for (JsonNode item : array(response, "WorkLogs", "WorkLogList", "IssueWorkLogs", "IssueWorkLogList")) {
            result.add(new CodingOpenApiPort.IssueWorklog(longValue(item, "Id", "WorkLogId"),
                    decimal(item, "RecordHours", "SpendHour"), decimal(item, "RemainingHours", "RemainingHour"),
                    longValue(item, "StartAt"), longValue(item, "CreatedAt"), longValue(item, "UpdatedAt")));
        }
        return result;
    }

    @Override
    public CodingOpenApiPort.Issue createIssue(String token, CodingOpenApiPort.CreateIssueRequest command) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ProjectName", command.projectName());
        request.put("Type", command.type());
        if (command.issueTypeId() > 0) request.put("IssueTypeId", command.issueTypeId());
        request.put("Name", command.name());
        request.put("Priority", StringUtils.defaultIfBlank(command.priority(), "0"));
        if (command.assigneeId() != null && command.assigneeId() > 0) {
            request.put("AssigneeId", command.assigneeId());
        }
        if (StringUtils.isNotBlank(command.description())) request.put("Description", command.description());
        if (command.parentCode() != null && command.parentCode() > 0) request.put("ParentCode", command.parentCode());
        if (command.workingHours() != null) request.put("WorkingHours", command.workingHours());
        if (!command.customFieldValues().isEmpty()) {
            request.put("CustomFieldValues", command.customFieldValues().stream()
                    .map(value -> Map.<String, Object>of("Id", value.id(), "Content", value.content())).toList());
        }
        JsonNode response = invoke(token, "CreateIssue", request);
        return toIssue(firstObject(response, "Issue", "Data"));
    }

    @Override
    public CodingOpenApiPort.Issue modifyIssue(String token, CodingOpenApiPort.ModifyIssueRequest command) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ProjectName", command.projectName());
        request.put("IssueCode", command.issueCode());
        if (StringUtils.isNotBlank(command.name())) request.put("Name", command.name());
        if (command.description() != null) request.put("Description", command.description());
        if (command.statusId() != null && command.statusId() > 0) request.put("StatusId", command.statusId());
        if (StringUtils.isNotBlank(command.priority())) request.put("Priority", command.priority());
        if (command.workingHours() != null) request.put("WorkingHours", command.workingHours());
        if (!command.customFieldValues().isEmpty()) {
            request.put("CustomFieldValues", command.customFieldValues().stream()
                    .map(value -> Map.<String, Object>of("Id", value.id(), "Content", value.content())).toList());
        }
        JsonNode response = invoke(token, "ModifyIssue", request);
        return toIssue(firstObject(response, "Issue", "Data"));
    }

    @Override
    public String createIssueWorkHours(String token, String projectName, long issueCode, BigDecimal spendHour,
                                       BigDecimal remainingHour, long startAt) {
        JsonNode response = invoke(token, "CreateIssueWorkHours", Map.of(
                "ProjectName", projectName,
                "IssueCode", issueCode,
                "SpendHour", spendHour,
                "RemainingHour", remainingHour,
                "StartAt", startAt));
        return text(response, "RequestId");
    }

    private CodingOpenApiPort.Issue toIssue(JsonNode item) {
        JsonNode typeDetail = item.path("IssueTypeDetail");
        JsonNode project = item.path("Project");
        JsonNode parent = item.path("Parent");
        String type = StringUtils.defaultIfBlank(text(item, "Type", "IssueType"),
                text(typeDetail, "SystemType", "Type", "Code"));
        String typeName = StringUtils.defaultIfBlank(text(typeDetail, "Name", "DisplayName", "TypeName"),
                text(item, "TypeName", "IssueTypeName"));
        Long parentCode = positiveLong(item, "ParentCode", "ParentIssueCode");
        if (parentCode == null && parent.isObject()) parentCode = positiveLong(parent, "Code", "IssueCode", "Id");
        boolean subtask = parentCode != null
                || type.toUpperCase(Locale.ROOT).contains("SUBTASK")
                || type.toUpperCase(Locale.ROOT).contains("SUB_TASK");
        JsonNode status = item.path("Status");
        String statusName = StringUtils.defaultIfBlank(text(item, "IssueStatusName", "StatusName"),
                status.isObject() ? text(status, "Name", "DisplayName") : text(item, "Status"));
        String statusType = StringUtils.defaultIfBlank(text(item, "IssueStatusType", "StatusType"),
                status.isObject() ? text(status, "Type", "StatusType") : "");
        String projectName = StringUtils.defaultIfBlank(text(project, "Name", "ProjectName"), "");
        String assigneeName = issueAssigneeName(item);
        return new CodingOpenApiPort.Issue(longValue(item, "Code", "IssueCode", "Id"), type, typeName,
                longValue(item, "IssueTypeId"), text(item, "Name", "Title", "Summary"),
                text(project, "DisplayName", "Name"), subtask,
                longValue(item, "Id", "IssueId", "Code"), statusName, statusType,
                decimal(item, "WorkingHours"), text(item, "Description", "Content"), parentCode,
                StringUtils.defaultIfBlank(text(parent, "ProjectName"), projectName),
                text(parent, "Type", "IssueType", "SystemType"),
                text(parent.path("IssueTypeDetail"), "Name", "DisplayName", "TypeName"),
                namedField(item, "开发团队", "DevelopmentTeam"),
                namedField(item, "DoD", "DOD", "Definition of Done"),
                namedField(item, "任务类型", "TaskType"), assigneeName, StringUtils.trimToNull(text(item, "Priority")),
                namedField(item, "是否线上Bug", "是否线上 Bug"), namedField(item, "Bug优先级", "Bug 优先级"));
    }

    private String issueAssigneeName(JsonNode item) {
        String direct = text(item, "AssigneeName", "AssigneeUserName", "HandlerName", "OwnerName", "ProcessorName");
        if (StringUtils.isNotBlank(direct)) return direct;
        for (String container : List.of("Assignee", "AssigneeUser", "AssigneeInfo", "Handler", "Owner", "Processor")) {
            JsonNode user = item.path(container);
            if (user.isTextual() && StringUtils.isNotBlank(user.asText())) return user.asText();
            String nested = text(user, "Name", "DisplayName", "NickName", "Nickname", "GlobalKey", "Username", "UserName");
            if (StringUtils.isNotBlank(nested)) return nested;
        }
        return "";
    }

    private Long positiveLong(JsonNode node, String... names) {
        long value = longValue(node, names);
        return value > 0 ? value : null;
    }

    private String namedField(JsonNode root, String... names) {
        Set<String> expected = new LinkedHashSet<>();
        for (String name : names) expected.add(normalizeFieldName(name));
        return namedField(root, expected, 0);
    }

    private String namedField(JsonNode node, Set<String> expected, int depth) {
        if (node == null || node.isMissingNode() || depth > 8) return null;
        if (node.isObject()) {
            String label = normalizeFieldName(text(node, "Name", "Title", "FieldName", "Label"));
            if (expected.contains(label)) {
                String value = text(node, "DisplayValue", "DisplayName", "OptionTitle", "Text");
                if (StringUtils.isNotBlank(value)) return value;
                value = text(node, "Content", "Value", "ValueString");
                if (StringUtils.isNotBlank(value)) return value;
                JsonNode raw = node.get("Value");
                if (raw != null && raw.isValueNode()) return raw.asText();
                if (raw != null && raw.isObject()) {
                    value = text(raw, "DisplayValue", "DisplayName", "OptionTitle", "Text",
                            "Title", "Name", "Content", "Value");
                    if (StringUtils.isNotBlank(value)) return value;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String value = namedField(fields.next().getValue(), expected, depth + 1);
                if (StringUtils.isNotBlank(value)) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String value = namedField(child, expected, depth + 1);
                if (StringUtils.isNotBlank(value)) return value;
            }
        }
        return null;
    }

    private String normalizeFieldName(String value) {
        return StringUtils.deleteWhitespace(StringUtils.defaultString(value)).replace("：", ":").toLowerCase(Locale.ROOT);
    }

    private CodingOpenApiPort.Member toMember(JsonNode item) {
        JsonNode user = item.path("User").isObject() ? item.path("User") : item;
        if (item.path("UserInfo").isObject()) {
            user = item.path("UserInfo");
        }
        long id = longValue(user, "Id", "UserId");
        if (id <= 0) {
            id = longValue(item, "UserId", "Id");
        }
        String status = text(item, "Status", "MemberStatus").toUpperCase(Locale.ROOT);
        boolean active = !item.path("Locked").asBoolean(false)
                && !item.path("IsLocked").asBoolean(false)
                && !item.path("Deleted").asBoolean(false)
                && (!item.has("Authorized") || item.path("Authorized").asBoolean(true))
                && (!item.has("IsAuthorized") || item.path("IsAuthorized").asBoolean(true))
                && !status.contains("LOCK") && !status.contains("DISABLE") && !status.contains("DELETE");
        return new CodingOpenApiPort.Member(id, text(user, "Name", "DisplayName", "GlobalKey"),
                text(user, "Avatar", "AvatarUrl"), active, departments(item));
    }

    private List<Department> departments(JsonNode item) {
        JsonNode departmentMember = item.path("DepartmentMember");
        List<JsonNode> refs = array(departmentMember, "Refs", "Departments");
        if (refs.isEmpty()) {
            refs = array(item, "DepartmentRefs", "Departments");
        }
        Map<Long, Department> departments = new LinkedHashMap<>();
        for (JsonNode ref : refs) {
            long id = longValue(ref, "DepartmentId", "Id");
            if (id <= 0) {
                continue;
            }
            String name = text(ref, "DepartmentName", "Name");
            String path = StringUtils.defaultIfBlank(text(ref, "DepartmentPath", "Path", "NamePath"), name);
            departments.putIfAbsent(id, new Department(id, name, path));
        }
        return new ArrayList<>(departments.values());
    }

    private List<CodingOpenApiPort.Member> deduplicateMembers(List<CodingOpenApiPort.Member> members) {
        Map<Long, CodingOpenApiPort.Member> result = new LinkedHashMap<>();
        for (CodingOpenApiPort.Member member : members) {
            result.merge(member.id(), member, (current, incoming) -> {
                Map<Long, Department> departments = new LinkedHashMap<>();
                current.departments().forEach(department -> departments.put(department.id(), department));
                incoming.departments().forEach(department -> departments.putIfAbsent(department.id(), department));
                return new CodingOpenApiPort.Member(current.id(), StringUtils.defaultIfBlank(current.name(), incoming.name()),
                        StringUtils.defaultIfBlank(current.avatar(), incoming.avatar()),
                        current.active() && incoming.active(), new ArrayList<>(departments.values()));
            });
        }
        return new ArrayList<>(result.values());
    }

    private String normalizeTeamHost(String host) {
        String normalized = StringUtils.removeEnd(StringUtils.trimToEmpty(host), "/");
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        return normalized;
    }

    private JsonNode firstObject(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (node.isObject()) {
                if ("Data".equals(name)) {
                    for (String nested : List.of("Iteration", "Issue")) {
                        if (node.path(nested).isObject()) return node.path(nested);
                    }
                }
                return node;
            }
        }
        return root;
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        String value = text(node, names);
        if (StringUtils.isBlank(value)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replaceAll("[^0-9.-]", ""));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private JsonNode invoke(String token, String action, Map<String, Object> parameters) {
        return invoke(token, action, parameters, Integer.MAX_VALUE);
    }

    private JsonNode invoke(String token, String action, Map<String, Object> parameters, long maxResponseBytes) {
        boolean readOnly = java.util.Set.of("DescribeMyDepots", "DescribeProjectDepotBranches",
                "DescribeGitCommitsInPage", "DescribeGitCommitDiff").contains(action);
        for (int attempt = 0; ; attempt++) {
            try {
                return invokeOnce(token, action, parameters, maxResponseBytes);
            } catch (CodingOpenApiException error) {
                String code = error.code();
                boolean transientFailure = code.startsWith("InternalError")
                        || code.startsWith("RequestLimitExceeded") || code.startsWith("HTTP_5")
                        || "HTTP_429".equals(code) || Integer.valueOf(429).equals(error.httpStatus())
                        || (error.httpStatus() != null && error.httpStatus() >= 500)
                        || error.getCause() instanceof java.io.IOException;
                if (!readOnly || !transientFailure || Thread.currentThread().isInterrupted()) throw error;
                if (attempt >= 2) {
                    throw new CodingOpenApiException(action, code,
                            "CODING 代码仓库服务暂时不可用，已重试 3 次，请稍后重新扫描",
                            error.httpStatus(), error.getCause());
                }
                try {
                    Thread.sleep(200L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CodingOpenApiException("CODING 请求已取消", interrupted);
                }
            }
        }
    }

    private JsonNode invokeOnce(String token, String action, Map<String, Object> parameters, long maxResponseBytes) {
        boolean acquired = false;
        Integer httpStatus = null;
        try {
            requestLimiter.acquire(token, action);
            acquired = true;
            ObjectNode body = objectMapper.createObjectNode();
            body.put("Action", action);
            parameters.forEach((key, value) -> body.set(key, objectMapper.valueToTree(value)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.safeApiUrl()))
                    .timeout(Duration.ofMillis("DescribeGitCommitDiff".equals(action)
                            ? Math.max(properties.getRequestTimeoutMs(), properties.getCodeDiffTimeoutMs())
                            : properties.getRequestTimeoutMs()))
                    .header(HttpHeaders.AUTHORIZATION, "token " + token)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<InputStream> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            httpStatus = httpResponse.statusCode();
            String responseBody;
            try (InputStream stream = httpResponse.body()) {
                byte[] bytes = readLimited(stream, maxResponseBytes);
                if (bytes == null) {
                    throw new CodingResponseTooLargeException(action, maxResponseBytes, httpResponse.statusCode());
                }
                responseBody = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            JsonNode response;
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                response = root == null ? objectMapper.missingNode() : root.path("Response");
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
                if (httpStatus < 200 || httpStatus >= 300) {
                    throw new CodingOpenApiException(action, "HTTP_" + httpStatus, "CODING 请求失败", httpStatus, null);
                }
                if ("DescribeGitCommitDiff".equals(action)) {
                    throw new CodingOpenApiException(action, "InvalidDiffResponse", "CODING 未返回可用的 diff", httpStatus, null);
                }
                throw invalidJson;
            }
            JsonNode error = response.path("Error");
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300 || !error.isMissingNode()) {
                String code = error.isMissingNode() ? "HTTP_" + httpResponse.statusCode() : text(error, "Code");
                String message = error.isMissingNode() ? "CODING 请求失败" : text(error, "Message", "Code");
                throw new CodingOpenApiException(action, code,
                        StringUtils.defaultIfBlank(message, "CODING 请求失败"), httpResponse.statusCode(), null);
            }
            return response;
        } catch (CodingOpenApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodingOpenApiException("CODING 请求已取消", e);
        } catch (Exception e) {
            throw new CodingOpenApiException(action, "", "CODING 服务暂不可用", httpStatus, e);
        } finally {
            if (acquired) requestLimiter.release(token);
        }
    }

    private byte[] readLimited(InputStream stream, long maxBytes) throws java.io.IOException {
        if (maxBytes >= Integer.MAX_VALUE) {
            return stream.readAllBytes();
        }
        byte[] bytes = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1));
        return bytes.length > maxBytes ? null : bytes;
    }

    private List<String> parents(JsonNode item) {
        List<String> result = new ArrayList<>();
        JsonNode parents = item.path("Parents");
        if (parents.isArray()) {
            for (JsonNode parent : parents) {
                String sha = parent.isTextual() ? parent.asText() : text(parent, "Sha", "Id");
                if (StringUtils.isNotBlank(sha)) result.add(sha);
            }
        }
        return result;
    }

    private int intValue(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.canConvertToInt()) return value.intValue();
            if (value.isTextual() && value.asText().matches("-?\\d+")) return Integer.parseInt(value.asText());
        }
        return 0;
    }

    private CodingPlan toPlan(JsonNode item, long projectId) {
        long id = longValue(item, "Id", "JobId");
        Long depotId = nullableLong(item, "DepotId", "RepositoryId", "RepoId");
        String depotType = StringUtils.defaultIfBlank(text(item, "DepotType"), "CODING");
        List<String> environments = environments(item);
        List<CodingBuildParameter> defaultParameters = defaultParameters(item);
        String defaultBranch = StringUtils.defaultIfBlank(
                text(item, "BranchSelector", "Branch", "DefaultBranch", "Ref"), "master");
        boolean supported = hasEnvironmentParameter(item) && depotId != null && depotId > 0;
        return new CodingPlan(id, text(item, "Name", "JobName"), projectId, depotId, depotType, defaultBranch,
                environments.isEmpty() ? List.of("sit", "uat", "prd") : environments, supported, defaultParameters,
                toBuild(item.path("LatestBuild").isMissingNode() ? item.path("LastBuild") : item.path("LatestBuild")));
    }

    private CodingBuild toBuild(JsonNode item) {
        if (item.isMissingNode() || item.isNull() || item.isEmpty()) {
            return null;
        }
        return new CodingBuild(longValue(item, "Id", "BuildId"), text(item, "Number", "BuildNumber", "Id"),
                text(item, "Status", "BuildStatus"), buildStatusDetail(item), buildBranch(item),
                abbreviateCommit(text(item, "CommitId", "Commit", "Sha")),
                buildUserName(item), duration(item),
                time(item, "CreatedAt", "StartTime", "StartedAt"), environment(item));
    }

    private String buildBranch(JsonNode item) {
        return StringUtils.removeStart(text(item, "Branch", "Ref", "BuildRef"), "refs/heads/");
    }

    private String buildStatusDetail(JsonNode item) {
        String status = text(item, "Status", "BuildStatus").toUpperCase(Locale.ROOT);
        if (!status.contains("ABORT") && !status.contains("CANCEL") && !status.contains("STOP")) {
            return "";
        }
        return String.join(" / ", List.of(text(item, "StatusNode"), text(item, "FailedMessage")).stream()
                .filter(StringUtils::isNotBlank)
                .toList());
    }

    private String buildUserName(JsonNode item) {
        String direct = text(item, "TriggerUserName", "CreatorName", "TriggerName", "TriggeredByName",
                "OperatorName", "UserName");
        if (StringUtils.isNotBlank(direct)) {
            return direct;
        }
        for (String container : List.of("TriggerUser", "Creator", "TriggerBy", "TriggeredBy", "Operator", "User")) {
            JsonNode user = item.path(container);
            if (user.isTextual() && StringUtils.isNotBlank(user.asText())) {
                return user.asText();
            }
            String nested = text(user, "Name", "DisplayName", "NickName", "Nickname", "GlobalKey",
                    "Username", "UserName");
            if (StringUtils.isNotBlank(nested)) {
                return nested;
            }
        }
        String cause = StringUtils.trimToEmpty(text(item, "Cause"));
        String manualTriggerSuffix = "手动触发";
        if (cause.endsWith(manualTriggerSuffix)) {
            return StringUtils.trimToEmpty(cause.substring(0, cause.length() - manualTriggerSuffix.length()));
        }
        return "";
    }

    private List<String> environments(JsonNode item) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode parameter : parameters(item)) {
            if (!"env".equalsIgnoreCase(text(parameter, "Name", "Key", "VariableName"))) {
                continue;
            }
            for (JsonNode value : array(parameter, "Values", "Options", "Enums")) {
                String text = value.isTextual() ? value.asText() : text(value, "Value", "Name", "Label");
                if (StringUtils.isNotBlank(text)) {
                    result.add(text);
                }
            }
            String defaultValue = text(parameter, "Value", "DefaultValue");
            if (StringUtils.isNotBlank(defaultValue)) {
                result.add(defaultValue);
            }
        }
        if (result.stream().anyMatch(this::isStandardEnvironment)) {
            return List.of("sit", "uat", "prd");
        }
        return new ArrayList<>(result);
    }

    private List<CodingBuildParameter> defaultParameters(JsonNode item) {
        List<CodingBuildParameter> result = new ArrayList<>();
        for (JsonNode parameter : parameters(item)) {
            String name = text(parameter, "Name", "Key", "VariableName");
            String value = text(parameter, "Value", "DefaultValue");
            if (StringUtils.isNotBlank(name)) {
                result.add(new CodingBuildParameter(name, value, parameter.path("Sensitive").asBoolean(false)));
            }
        }
        return result;
    }

    private List<Map<String, Object>> triggerParameters(List<CodingBuildParameter> defaultParameters, String environment) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean environmentOverridden = false;
        for (CodingBuildParameter parameter : defaultParameters) {
            boolean environmentParameter = "env".equalsIgnoreCase(parameter.name());
            result.add(Map.of(
                    "Name", parameter.name(),
                    "Value", environmentParameter ? environment : parameter.value(),
                    "Sensitive", parameter.sensitive()));
            environmentOverridden |= environmentParameter;
        }
        if (!environmentOverridden) {
            result.add(Map.of("Name", "env", "Value", environment, "Sensitive", false));
        }
        return result;
    }

    private boolean hasEnvironmentParameter(JsonNode item) {
        return parameters(item).stream()
                .anyMatch(parameter -> "env".equalsIgnoreCase(text(parameter, "Name", "Key", "VariableName")));
    }

    private List<JsonNode> parameters(JsonNode item) {
        List<JsonNode> result = new ArrayList<>();
        for (String name : List.of("ParamList", "EnvList", "Parameters", "BuildParameters", "Params")) {
            result.addAll(array(item, name));
        }
        return result;
    }

    private String environment(JsonNode item) {
        for (JsonNode parameter : parameters(item)) {
            if ("env".equalsIgnoreCase(text(parameter, "Name", "Key", "VariableName"))) {
                return StringUtils.trimToEmpty(text(parameter, "Value", "DefaultValue"));
            }
        }
        return "";
    }

    private boolean isStandardEnvironment(String environment) {
        return "sit".equalsIgnoreCase(environment) || "uat".equalsIgnoreCase(environment)
                || "prd".equalsIgnoreCase(environment);
    }

    private List<JsonNode> array(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (node.isArray()) {
                List<JsonNode> result = new ArrayList<>();
                node.forEach(result::add);
                return result;
            }
        }
        return Collections.emptyList();
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return "";
    }

    private long longValue(JsonNode node, String... names) {
        Long value = nullableLong(node, names);
        return value == null ? 0L : value;
    }

    private Long nullableLong(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.canConvertToLong()) {
                return value.longValue();
            }
            if (value.isTextual() && StringUtils.isNumeric(value.asText())) {
                return Long.parseLong(value.asText());
            }
        }
        return null;
    }

    private String abbreviateCommit(String commit) {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private String duration(JsonNode item) {
        JsonNode value = item.path("Duration");
        if (value.canConvertToLong()) {
            long seconds = value.longValue() / 1000;
            return seconds / 60 + "分" + seconds % 60 + "秒";
        }
        return text(item, "DurationText", "Duration");
    }

    private String time(JsonNode item, String... names) {
        Long epoch = nullableLong(item, names);
        if (epoch != null && epoch > 1000000000L) {
            if (epoch < 100000000000L) {
                epoch *= 1000;
            }
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CHINA_ZONE).format(Instant.ofEpochMilli(epoch));
        }
        return text(item, names);
    }

    private Long epochMillis(JsonNode item, String... names) {
        Long epoch = nullableLong(item, names);
        if (epoch != null) {
            return epoch < 100000000000L ? epoch * 1000 : epoch;
        }
        String value = text(item, names);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (RuntimeException ignoredAgain) {
                for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")) {
                    try {
                        return java.time.LocalDateTime.parse(value,
                                        DateTimeFormatter.ofPattern(pattern).withZone(CHINA_ZONE))
                                .atZone(CHINA_ZONE).toInstant().toEpochMilli();
                    } catch (RuntimeException ignoredPattern) {
                        // 继续尝试下一个常见格式。
                    }
                }
                return null;
            }
        }
    }
}
