package com.itwray.iw.external.zhaogang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.external.zhaogang.ai.ZhaogangAiConfigService;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Actor;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.IterationDetail;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Permissions;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.ReleasePlan;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.Stage;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModels.UserSnapshot;
import com.itwray.iw.external.zhaogang.iteration.TeamIterationModule;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.MatchCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.AddItem;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.BatchAddCommand;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.RecognizedRow;
import com.itwray.iw.external.zhaogang.releaseimport.ReleaseImportModels.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZhaogangReleaseImportServiceTest {

    private final ZhaogangAiConfigService aiConfig = mock(ZhaogangAiConfigService.class);
    private final ZhaogangCatalogService catalog = mock(ZhaogangCatalogService.class);
    private final TeamIterationModule iterations = mock(TeamIterationModule.class);
    private final ZhaogangProperties properties = new ZhaogangProperties();
    private final ZhaogangReleaseImportService service = new ZhaogangReleaseImportService(aiConfig, catalog,
            iterations, new ObjectMapper(), properties);

    @Test
    void matchesOpsAsProjectAndSystemNameAsPlanWithoutTreatingRequirementAsProject() {
        when(catalog.catalog(org.mockito.ArgumentMatchers.any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "order-service", "订单服务")),
                List.of(new ZhaogangModels.Plan(2, 1, "order-service", "订单服务", "order-service.ui",
                        "sit", List.of("sit"), true, null)), List.of(), "now", false));
        when(iterations.detail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(new IterationDetail(9, "", "iteration", "", Stage.NOT_STARTED, null, null, null,
                        new UserSnapshot(7, "user", ""), List.of(), List.of(), List.of(), 1, null, null,
                        new Permissions(true, true, true)));

        var preview = service.match(new Actor(7, "user", "", "token", "team", 8, "https://team.coding.net"), 9,
                new MatchCommand(List.of(new RecognizedRow("项目列中的需求描述", "订单服务", "order-service.ui", "", ""))));

        assertThat(preview.items()).singleElement().extracting(item -> item.status()).isEqualTo(Status.READY);
        assertThat(preview.items().get(0).projectId()).isEqualTo(1L);
        assertThat(preview.items().get(0).planId()).isEqualTo(2L);
    }

    @Test
    void matchesProjectNameAndDisplayNameCaseInsensitivelyButOnlyByExactValue() {
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(
                        new ZhaogangModels.Project(1, "Order-Service", "订单服务"),
                        new ZhaogangModels.Project(2, "member-service", "MEMBER CENTER"),
                        new ZhaogangModels.Project(3, "order-service-api", "订单服务 API")),
                List.of(
                        plan(2, 1, "order.service"),
                        plan(3, 2, "member.service"),
                        plan(4, 3, "order-api.service")),
                List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        var preview = service.match(actor(), 9, new MatchCommand(List.of(
                row("ORDER-SERVICE", "order"),
                row("member center", "member"),
                row("order", "order-api"))));

        assertThat(preview.items()).extracting(item -> item.projectId()).containsExactly(1L, 2L, null);
        assertThat(preview.items()).extracting(item -> item.status())
                .containsExactly(Status.READY, Status.READY, Status.UNMATCHED);
    }

    @Test
    void matchesBaseServiceNameToPlansWithServiceUiAndBuildSuffixes() {
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "ops-project", "OPS Project")),
                List.of(
                        plan(2, 1, "online.user.service.deploy"),
                        plan(3, 1, "online.admin.ui-build")),
                List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        var preview = service.match(actor(), 9, new MatchCommand(List.of(
                row("ops-project", "online.user"),
                row("ops-project", "online-admin-ui"))));

        assertThat(preview.items()).extracting(item -> item.planId()).containsExactly(2L, 3L);
        assertThat(preview.items()).extracting(item -> item.status()).containsExactly(Status.READY, Status.READY);
    }

    @Test
    void marksBaseServiceNameAmbiguousWhenServiceAndUiPlansBothExist() {
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "ops-project", "OPS Project")),
                List.of(
                        plan(2, 1, "online.user.service.deploy"),
                        plan(3, 1, "online.user.ui.build")),
                List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        var preview = service.match(actor(), 9, new MatchCommand(List.of(
                row("ops-project", "online.user"),
                row("ops-project", "online.user.service"))));

        assertThat(preview.items().get(0)).satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.PLAN_AMBIGUOUS);
            assertThat(item.candidates()).hasSize(2);
        });
        assertThat(preview.items().get(1).status()).isEqualTo(Status.READY);
        assertThat(preview.items().get(1).planId()).isEqualTo(2L);
    }

    @Test
    void prefersUniqueBuildablePlanOverUnbuildablePlanWithSameServiceName() {
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "ops-project", "OPS Project")),
                List.of(
                        plan(2, 1, "online.base.saasbusinessmanager.service_docker", true),
                        plan(3, 1, "online.base.saasbusinessmanager.service_mit", false)),
                List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        var preview = service.match(actor(), 9, new MatchCommand(List.of(
                row("ops-project", "online.base.saasbusinessmanager.service"))));

        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.READY);
            assertThat(item.planId()).isEqualTo(2L);
        });
    }

    @Test
    void doesNotUseBroadContainsForPlanMatching() {
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "ops-project", "OPS Project")),
                List.of(plan(2, 1, "preorder.service.deploy")), List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        var preview = service.match(actor(), 9,
                new MatchCommand(List.of(row("ops-project", "order"))));

        assertThat(preview.items()).singleElement().extracting(item -> item.status()).isEqualTo(Status.UNMATCHED);
    }

    @Test
    void recognizeUsesCleanedDynamicColumnNamesInPrompt() throws Exception {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(aiConfig.vision(eq(8L), eq(7L), any(byte[].class), eq("image/png"), prompt.capture()))
                .thenReturn(new ObjectMapper().readTree("""
                        {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\\"rows\\\":[]}"}]}]}
                        """));
        when(catalog.catalog(any())).thenReturn(new ZhaogangModels.PlanCatalog(List.of(), List.of(),
                List.of(), "now", false));
        when(iterations.detail(any(), eq(9L))).thenReturn(emptyIteration());

        service.recognizeAndMatch(actor(), 9, new byte[]{1}, "image/png",
                "  所属\n OPS\"列  ", "  ");

        assertThat(prompt.getValue()).contains("源项目名称列名为 \"所属 OPS\\\"列\"")
                .contains("源构建计划列名为 \"系统名字\"")
                .doesNotContain("所属\n OPS");
    }

    @Test
    void marksDuplicateAndAlreadyAddedRows() {
        when(catalog.catalog(org.mockito.ArgumentMatchers.any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "order-service", "订单服务")),
                List.of(new ZhaogangModels.Plan(2, 1, "order-service", "订单服务", "order-service.ui",
                        "sit", List.of("sit"), true, null)), List.of(), "now", false));
        ReleasePlan added = new ReleasePlan(10, 1, "order-service", "订单服务", 2, "order-service.ui", true,
                new UserSnapshot(7, "user", ""), null);
        when(iterations.detail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(new IterationDetail(9, "", "iteration", "", Stage.NOT_STARTED, null, null, null,
                        new UserSnapshot(7, "user", ""), List.of(), List.of(), List.of(added), 1, null, null,
                        new Permissions(true, true, true)));

        var preview = service.match(new Actor(7, "user", "", "token", "team", 8, "https://team.coding.net"), 9,
                new MatchCommand(List.of(
                        new RecognizedRow("a", "订单服务", "order-service.ui", "", ""),
                        new RecognizedRow("b", "订单服务", "order-service.ui", "", ""))));

        assertThat(preview.items()).extracting(item -> item.status()).containsExactly(Status.ALREADY_ADDED, Status.ALREADY_ADDED);
    }

    @Test
    void marksDuplicateInImageAndUnbuildablePlans() {
        when(catalog.catalog(org.mockito.ArgumentMatchers.any())).thenReturn(new ZhaogangModels.PlanCatalog(
                List.of(new ZhaogangModels.Project(1, "order-service", "订单服务")),
                List.of(
                        new ZhaogangModels.Plan(2, 1, "order-service", "订单服务", "order-service.ui",
                                "sit", List.of("sit"), true, null),
                        new ZhaogangModels.Plan(3, 1, "order-service", "订单服务", "legacy-plan",
                                "", List.of(), false, null)), List.of(), "now", false));
        when(iterations.detail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(emptyIteration());

        var preview = service.match(actor(), 9, new MatchCommand(List.of(
                new RecognizedRow("a", "订单服务", "order-service.ui", "", ""),
                new RecognizedRow("b", "订单服务", "order-service.ui", "", ""),
                new RecognizedRow("c", "订单服务", "legacy-plan", "", ""))));

        assertThat(preview.items()).extracting(item -> item.status())
                .containsExactly(Status.READY, Status.DUPLICATE_IN_IMAGE, Status.UNBUILDABLE);
    }

    @Test
    void returnsCatalogUnavailableInsteadOfInventingMatches() {
        when(catalog.catalog(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("CODING unavailable"));

        var preview = service.match(actor(), 9,
                new MatchCommand(List.of(new RecognizedRow("a", "订单服务", "order-service.ui", "", ""))));

        assertThat(preview.items()).singleElement().extracting(item -> item.status()).isEqualTo(Status.CATALOG_UNAVAILABLE);
    }

    @Test
    void batchAddKeepsSuccessesWhenLaterRowsFailPermissionValidation() {
        when(iterations.addReleasePlan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(ReleasePlan.class))
                .thenThrow(new IllegalArgumentException("没有迭代发布权限"));

        var result = service.add(actor(), 9, new BatchAddCommand(List.of(
                new AddItem(1, 1, 2), new AddItem(2, 1, 3))));

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures()).singleElement().extracting(item -> item.reason())
                .isEqualTo("没有迭代发布权限");
    }

    private Actor actor() {
        return new Actor(7, "user", "", "token", "team", 8, "https://team.coding.net");
    }

    private IterationDetail emptyIteration() {
        return new IterationDetail(9, "", "iteration", "", Stage.NOT_STARTED, null, null, null,
                new UserSnapshot(7, "user", ""), List.of(), List.of(), List.of(), 1, null, null,
                new Permissions(true, true, true));
    }

    private RecognizedRow row(String project, String plan) {
        return new RecognizedRow("", project, plan, "", "");
    }

    private ZhaogangModels.Plan plan(long id, long projectId, String name) {
        return plan(id, projectId, name, true);
    }

    private ZhaogangModels.Plan plan(long id, long projectId, String name, boolean quickBuildSupported) {
        return new ZhaogangModels.Plan(id, projectId, "project", "Project", name,
                "main", List.of("sit"), quickBuildSupported, null);
    }
}
