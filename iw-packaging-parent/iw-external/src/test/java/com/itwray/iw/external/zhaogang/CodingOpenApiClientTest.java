package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingOpenApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void projectsQueriesOnlyProjectsJoinedByCurrentUser() throws Exception {
        long userId = 183478L;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-api", exchange -> respondToProjectQuery(exchange, userId));
        server.start();

        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/open-api");
        CodingOpenApiClient client = new CodingOpenApiClient(properties, objectMapper);

        List<CodingProject> projects = client.projects("test-token", userId);

        assertEquals(1, projects.size());
        assertEquals(6196835L, projects.get(0).id());
        assertEquals("ops-panmaoyuncaixiao", projects.get(0).name());
    }

    @Test
    void teamDirectoryUsesOfficialDepartmentContract() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            if ("DescribeTeam".equals(request.path("Action").asText())) {
                respond(exchange, """
                        {"Response":{"Data":{"Id":100,"Name":"产业数字中心","TeamHost":"g-iijw5014.coding.net"}}}
                        """);
                return;
            }
            boolean valid = "DescribeTeamMembers".equals(request.path("Action").asText())
                    && request.path("PageNumber").asInt() == 1
                    && request.path("PageSize").asInt() == 100
                    && request.path("ShowDepartment").asBoolean();
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Data":{"TotalCount":1,"TeamMembers":[{"User":{"Id":9292850,"Name":"步步(王发瑞)","Avatar":"avatar"},"DepartmentMember":{"Refs":[{"DepartmentId":300,"DepartmentName":"基础服务组","DepartmentPath":"研发中心 / 基础服务组"}]}}]}}}
                    """);
        });

        CodingOpenApiPort.Team team = client.team("test-token");
        CodingOpenApiPort.TeamDirectory directory = client.teamDirectory("test-token");

        assertEquals("https://g-iijw5014.coding.net", team.host());
        assertEquals(1, directory.members().size());
        assertEquals("研发中心 / 基础服务组", directory.members().get(0).departments().get(0).path());
    }

    @Test
    void worklogPageSendsTimestampUserAndOffsetAndParsesOfficialFields() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeAllProjectsIssueWorkLogList".equals(request.path("Action").asText())
                    && request.path("StartAt").asLong() == 1785513600000L
                    && request.path("EndAt").asLong() == 1788192000000L
                    && request.path("UserId").asLong() == 9292850L
                    && request.path("Offset").asInt() == 1000
                    && request.path("Limit").asInt() == 1000
                    && !request.has("StartDate") && !request.has("PageNumber");
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"WorkLogs":[{"Id":489,"IssueId":7000,"IssueCode":7234,"ProjectName":"yuncunzheng","UserId":9292850,"RecordHours":"2.5","WorkingDesc":"完成接口联调","StartAt":1787356800000,"CreatedAt":1787385600000,"UpdatedAt":1787389200000}]}}
                    """);
        });

        CodingOpenApiPort.WorklogPage page = client.worklogPage("test-token", 1785513600000L,
                1788192000000L, 9292850L, 1000, 1000);

        assertEquals(1, page.items().size());
        assertEquals(new BigDecimal("2.5"), page.items().get(0).hours());
        assertEquals(1787356800000L, page.items().get(0).startAt());
    }

    @Test
    void issueReadsTypeDetailAndSubtaskRelationship() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Issue":{"Id":9001,"Code":7234,"Name":"解决采购单价问题","Type":"MISSION","ParentIssueCode":"7000","Status":{"Name":"开发中","Type":"PROCESSING"},"IssueTypeDetail":{"Name":"子任务"},"Project":{"DisplayName":"基础服务阿米巴"}}}}
                """));

        CodingOpenApiPort.Issue issue = client.issue("test-token", "yuncunzheng", 7234L);

        assertEquals("MISSION", issue.type());
        assertEquals("子任务", issue.typeName());
        assertTrue(issue.subtask());
        assertEquals(9001L, issue.id());
        assertEquals("开发中", issue.statusName());
    }

    @Test
    void issueReadsOfficialIssueStatusFields() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Issue":{"Id":7550,"Code":7550,"Name":"进项发票核销","Type":"REQUIREMENT","IssueStatusName":"开发中","IssueStatusType":"PROCESSING","IssueTypeDetail":{"Id":4,"Name":"需求","IssueType":"REQUIREMENT"},"Project":{"Name":"yuncunzheng","DisplayName":"基础服务阿米巴"}}}}
                """));

        CodingOpenApiPort.Issue issue = client.issue("test-token", "yuncunzheng", 7550L);

        assertEquals("开发中", issue.statusName());
        assertEquals("PROCESSING", issue.statusType());
    }

    @Test
    void issueReadsAssigneeNameFromNestedUser() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Issue":{"Id":7550,"Code":7550,"Name":"进项发票核销","Type":"REQUIREMENT","Assignee":{"Id":9292850,"Name":"安德(孙乃鹏)"},"IssueTypeDetail":{"Name":"需求"},"Project":{"Name":"yuncunzheng","DisplayName":"基础服务阿米巴"}}}}
                """));

        CodingOpenApiPort.Issue issue = client.issue("test-token", "yuncunzheng", 7550L);

        assertEquals("安德(孙乃鹏)", issue.assigneeName());
    }

    @Test
    void issueReadsCustomFieldDisplayValuesInsteadOfCodingOptionIds() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Issue":{"Id":7778,"Code":7778,"Name":"用户故事","Type":"REQUIREMENT",
                  "IssueTypeDetail":{"Name":"用户故事"},"Project":{"Name":"project-a","DisplayName":"项目A"},
                  "CustomFieldValues":[
                    {"Name":"开发团队","Value":"1056188","DisplayValue":"基础服务组"},
                    {"Name":"DoD","Value":"1010715","DisplayValue":"测试通过"}
                  ]}}}
                """));

        CodingOpenApiPort.Issue issue = client.issue("test-token", "project-a", 7778L);

        assertEquals("基础服务组", issue.developmentTeam());
        assertEquals("测试通过", issue.definitionOfDone());
    }

    @Test
    void createIssueUsesParentOnlyForSubTasks() throws Exception {
        List<JsonNode> requests = new java.util.ArrayList<>();
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            requests.add(request);
            long code = requests.size() == 1 ? 8001L : 8002L;
            String type = request.path("Type").asText();
            respond(exchange, "{\"Response\":{\"Issue\":{\"Id\":" + (code + 10000)
                    + ",\"Code\":" + code + ",\"Name\":\"测试事项\",\"Type\":\"" + type
                    + "\",\"IssueTypeDetail\":{\"Name\":\"" + ("DEFECT".equals(type) ? "缺陷" : "子工作项")
                    + "\"},\"Project\":{\"DisplayName\":\"项目A\"}}}}}");
        });

        client.createIssue("test-token", new CodingOpenApiPort.CreateIssueRequest("project-a", "SUB_TASK", 31L,
                7000L, "实现接口", null, "0", new BigDecimal("8.5"),
                List.of(new CodingOpenApiPort.CustomFieldValue(201L, "backend"))));
        client.createIssue("test-token", new CodingOpenApiPort.CreateIssueRequest("project-a", "DEFECT", 41L,
                null, "价格错误", "复现步骤", "2", null, List.of()));

        JsonNode subTask = requests.get(0);
        assertEquals("CreateIssue", subTask.path("Action").asText());
        assertEquals("0", subTask.path("Priority").asText());
        assertEquals(31L, subTask.path("IssueTypeId").asLong());
        assertEquals(7000L, subTask.path("ParentCode").asLong());
        assertEquals("8.5", subTask.path("WorkingHours").asText());
        assertEquals(201L, subTask.path("CustomFieldValues").path(0).path("Id").asLong());
        assertEquals("backend", subTask.path("CustomFieldValues").path(0).path("Content").asText());
        JsonNode defect = requests.get(1);
        assertEquals("DEFECT", defect.path("Type").asText());
        assertTrue(!defect.has("ParentCode"));
        assertEquals("2", defect.path("Priority").asText());
        assertEquals("复现步骤", defect.path("Description").asText());
    }

    @Test
    void createIssueSerializesAssigneeId() throws Exception {
        List<JsonNode> requests = new java.util.ArrayList<>();
        CodingOpenApiClient client = client(exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"Response\":{\"Issue\":{\"Id\":18003,\"Code\":8003,"
                    + "\"Name\":\"核销异常处理故事\",\"Type\":\"REQUIREMENT\"}}}");
        });

        client.createIssue("test-token", new CodingOpenApiPort.CreateIssueRequest("project-a", "REQUIREMENT", 11L,
                7550L, "核销异常处理故事", null, "0", 100L, null, List.of()));

        assertEquals(100L, requests.get(0).path("AssigneeId").asLong());
    }

    @Test
    void modifyIssueWritesCodingStatusId() throws Exception {
        List<JsonNode> requests = new java.util.ArrayList<>();
        CodingOpenApiClient client = client(exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"Response\":{\"Issue\":{\"Id\":18003,\"Code\":8003,"
                    + "\"Name\":\"核销异常处理故事\",\"Type\":\"REQUIREMENT\"}}}");
        });

        client.modifyIssue("test-token", new CodingOpenApiPort.ModifyIssueRequest("project-a", 8003L,
                null, null, 9L, null, null, List.of()));

        JsonNode request = requests.get(0);
        assertEquals("ModifyIssue", request.path("Action").asText());
        assertEquals("project-a", request.path("ProjectName").asText());
        assertEquals(8003L, request.path("IssueCode").asLong());
        assertEquals(9L, request.path("StatusId").asLong());
    }

    @Test
    void issueStatusesUseProjectIssueStatusContract() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeProjectIssueStatusList".equals(request.path("Action").asText())
                    && "project-a".equals(request.path("ProjectName").asText())
                    && "REQUIREMENT".equals(request.path("IssueType").asText())
                    && request.path("IssueTypeId").asLong() == 31L;
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"ProjectIssueStatusList":[{"IssueStatusId":9,
                      "IssueStatus":{"Name":"开发中","Type":"PROCESSING"}}]}}
                    """);
        });

        List<CodingOpenApiPort.IssueStatus> statuses = client.issueStatuses("test-token", "project-a",
                "REQUIREMENT", 31L);

        assertEquals(1, statuses.size());
        assertEquals(9L, statuses.get(0).id());
        assertEquals("开发中", statuses.get(0).name());
        assertEquals("PROCESSING", statuses.get(0).type());
    }

    @Test
    void issueFieldsReadOptionsAndOfficialFieldMetadata() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"ProjectIssueFieldList":[{"IssueFieldId":201,"Required":true,"NeedDefault":false,
                  "IssueField":{"Name":"开发团队","Type":"CUSTOM","ComponentType":"SELECT",
                  "Options":[{"Title":"基础服务组","Value":"backend"}]}}]}}
                """));

        List<CodingOpenApiPort.IssueField> fields = client.issueFields("test-token", "project-a",
                "REQUIREMENT", 31L);

        assertEquals(1, fields.size());
        assertEquals(201L, fields.get(0).id());
        assertTrue(fields.get(0).required());
        assertEquals("backend", fields.get(0).options().get(0).value());
    }

    @Test
    void createIssueWorkHoursUsesHoursAndTimestamp() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertEquals("CreateIssueWorkHours", request.path("Action").asText());
            assertEquals("2.5", request.path("SpendHour").asText());
            assertEquals("5.5", request.path("RemainingHour").asText());
            assertEquals(1787711400000L, request.path("StartAt").asLong());
            respond(exchange, "{\"Response\":{\"RequestId\":\"worklog-1\"}}");
        });

        String requestId = client.createIssueWorkHours("test-token", "project-a", 8001L,
                new BigDecimal("2.5"), new BigDecimal("5.5"), 1787711400000L);

        assertEquals("worklog-1", requestId);
    }

    @Test
    void issueTypesReadsProjectSystemAndCustomTypes() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"IssueTypes":[
                  {"Id":1,"Name":"用户故事","IssueType":"REQUIREMENT","IsSystem":false},
                  {"Id":31,"Name":"子工作项","IssueType":"SUB_TASK","IsSystem":true}
                ]}}
                """));

        List<CodingOpenApiPort.IssueType> types = client.issueTypes("test-token", "project-a");

        assertEquals(2, types.size());
        assertEquals("用户故事", types.get(0).name());
        assertEquals("SUB_TASK", types.get(1).systemType());
        assertTrue(types.get(1).system());
    }

    @Test
    void planRecognizesStandardEnvListAsQuickBuild() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeCodingCIJob".equals(request.path("Action").asText())
                    && request.path("JobId").asLong() == 6196835L
                    && !request.has("ProjectId");
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Job":{"Id":6196835,"ProjectId":450,"DepotId":1,"DepotType":"CODING","Name":"online.base.service","BranchSelector":"master","EnvList":[{"Name":"DOCKER_IMAGE_VERSION","Value":"${GIT_LOCAL_BRANCH}","Sensitive":false},{"Name":"env","Value":"sit","Sensitive":false},{"Name":"prd_tke_voucher","Value":"masked","Sensitive":true}]}}}
                    """);
        });

        CodingPlan plan = client.plan("test-token", 450L, 6196835L);

        assertTrue(plan.quickBuildSupported());
        assertEquals("master", plan.defaultBranch());
        assertEquals(List.of("sit", "uat", "prd"), plan.environments());
    }

    @Test
    void branchesUseOfficialDepotContractAndFilterLocally() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeProjectDepotBranches".equals(request.path("Action").asText())
                    && request.path("ProjectId").asLong() == 450L
                    && request.path("Id").asLong() == 1L
                    && "CODING".equals(request.path("DepotType").asText());
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Data":{"DepotDetailList":[{"Name":"master","Sha":"aaa"},{"Name":"release_20260821","Sha":"bbb"},{"Name":"test","Sha":"ccc"}]}}}
                    """);
        });

        List<CodingBranch> branches = client.branches("test-token", 450L, 1L, "CODING", "release");

        assertEquals(List.of(new CodingBranch("release_20260821")), branches);
    }

    @Test
    void triggerBuildOnlyOverridesEnvAndKeepsPlanDefaults() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            if ("DescribeCodingCIJob".equals(request.path("Action").asText())) {
                respond(exchange, """
                        {"Response":{"Job":{"Id":6196835,"ProjectId":450,"DepotId":1,"DepotType":"CODING","Name":"online.base.service","BranchSelector":"master","EnvList":[{"Name":"DOCKER_IMAGE_VERSION","Value":"${GIT_LOCAL_BRANCH}","Sensitive":false},{"Name":"env","Value":"sit","Sensitive":false},{"Name":"prd_tke_voucher","Value":"plan-default","Sensitive":true}]}}}
                        """);
                return;
            }
            JsonNode parameters = request.path("ParamList");
            boolean valid = "TriggerCodingCIBuild".equals(request.path("Action").asText())
                    && request.path("JobId").asLong() == 6196835L
                    && "release_20260821".equals(request.path("Revision").asText())
                    && parameters.size() == 3
                    && parameterMatches(parameters.path(0), "DOCKER_IMAGE_VERSION", "${GIT_LOCAL_BRANCH}", false)
                    && parameterMatches(parameters.path(1), "env", "prd", false)
                    && parameterMatches(parameters.path(2), "prd_tke_voucher", "plan-default", true)
                    && !request.has("ProjectId") && !request.has("Branch") && !request.has("Parameters");
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Data":{"Build":{"Id":2919,"Number":2919,"Status":"QUEUED","CommitId":"6866f42abc","CreatedAt":1787302800000}}}}
                    """);
        });

        ZhaogangProperties properties = new ZhaogangProperties();
        ZhaogangWorkbenchService service = new ZhaogangWorkbenchService(client, properties);
        ZhaogangTriggerBuildDto dto = new ZhaogangTriggerBuildDto();
        dto.setBranch("release_20260821");
        dto.setEnvironment("prd");

        ZhaogangModels.Build build = service.triggerBuild(
                new ZhaogangSession("test-token", 183478L, "tester", "", "g-iijw5014"), 450L, 6196835L, dto);

        assertEquals(2919L, build.id());
        assertEquals("QUEUED", build.status());
        assertEquals("release_20260821", build.branch());
    }

    private boolean parameterMatches(JsonNode parameter, String name, String value, boolean sensitive) {
        return name.equals(parameter.path("Name").asText())
                && value.equals(parameter.path("Value").asText())
                && parameter.path("Sensitive").isBoolean()
                && sensitive == parameter.path("Sensitive").asBoolean();
    }

    @Test
    void buildsReadOfficialNestedBuildList() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeCodingCIBuilds".equals(request.path("Action").asText())
                    && request.path("JobId").asLong() == 6196835L
                    && request.path("PageNumber").asInt() == 1
                    && request.path("PageSize").asInt() == 20
                    && !request.has("ProjectId");
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Data":{"BuildList":[{"Id":2919,"Number":2919,"Status":"SUCCEED","Branch":"test","CommitId":"6866f42abc","Duration":414000,"CreatedAt":1787302800000}]}}}
                    """);
        });

        List<CodingBuild> builds = client.builds("test-token", 450L, 6196835L);

        assertEquals(1, builds.size());
        assertEquals("6866f42", builds.get(0).commit());
    }

    @Test
    void buildsReadBranchFromBuildRefWhenBranchIsEmpty() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Data":{"BuildList":[{"Id":62093135,"Number":3245,"Status":"SUCCEED","Branch":"","BuildRef":"refs/heads/test"}]}}}
                """));

        CodingBuild build = client.latestBuild("test-token", 14878869L, 6240096L);

        assertEquals("test", build.branch());
    }

    @Test
    void abortedBuildsExposeCodingStageAndTerminationMessage() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Data":{"BuildList":[{"Id":62091363,"Number":3241,"Status":"ABORTED","StatusNode":"编译","FailedMessage":"由用户 程都 终止"}]}}}
                """));

        CodingBuild build = client.latestBuild("test-token", 14878869L, 6240096L);

        assertEquals("编译 / 由用户 程都 终止", build.statusDetail());
    }

    @Test
    void latestBuildRequestsOnlyOneRecord() throws Exception {
        CodingOpenApiClient client = client(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean valid = "DescribeCodingCIBuilds".equals(request.path("Action").asText())
                    && request.path("JobId").asLong() == 6196835L
                    && request.path("PageNumber").asInt() == 1
                    && request.path("PageSize").asInt() == 1;
            if (!valid) {
                respondContractError(exchange);
                return;
            }
            respond(exchange, """
                    {"Response":{"Data":{"BuildList":[{"Id":2920,"Number":2920,"Status":"RUNNING","Branch":"master"}]}}}
                    """);
        });

        CodingBuild build = client.latestBuild("test-token", 450L, 6196835L);

        assertEquals(2920L, build.id());
        assertEquals("RUNNING", build.status());
    }

    @Test
    void buildsReadTriggerUserNameFromNestedUserObject() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Data":{"BuildList":[{"Id":2921,"Number":2921,"Status":"SUCCEED","Branch":"master","TriggerUser":{"Id":183478,"Name":"步步(王发瑞)"}}]}}}
                """));

        CodingBuild build = client.latestBuild("test-token", 450L, 6196835L);

        assertEquals("步步(王发瑞)", build.triggerUser());
    }

    @Test
    void buildsReadTriggerUserNameFromManualTriggerCause() throws Exception {
        CodingOpenApiClient client = client(exchange -> respond(exchange, """
                {"Response":{"Data":{"BuildList":[{"Id":62065229,"Number":276,"Status":"SUCCEED","Cause":"步步(王发瑞) 手动触发"}]}}}
                """));

        CodingBuild build = client.latestBuild("test-token", 14878869L, 7011717L);

        assertEquals("步步(王发瑞)", build.triggerUser());
    }

    private CodingOpenApiClient client(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-api", handler);
        server.start();
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/open-api");
        return new CodingOpenApiClient(properties, objectMapper);
    }

    private void respondToProjectQuery(HttpExchange exchange, long expectedUserId) throws IOException {
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        boolean userProjectQuery = "DescribeUserProjects".equals(request.path("Action").asText())
                && expectedUserId == request.path("UserId").asLong();
        if (!userProjectQuery) {
            respond(exchange, """
                    {"Response":{"Error":{"Code":"UnauthorizedOperation","Message":"无权访问，请联系团队管理员为您设置权限"}}}
                    """);
            return;
        }
        respond(exchange, """
                {"Response":{"ProjectList":[{"Id":6196835,"Name":"ops-panmaoyuncaixiao","DisplayName":"ops-胖猫云采销"}]}}
                """);
    }

    private void respondContractError(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {"Response":{"Error":{"Code":"InvalidParameter","Message":"CODING OpenAPI 请求参数不符合官方契约"}}}
                """);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
