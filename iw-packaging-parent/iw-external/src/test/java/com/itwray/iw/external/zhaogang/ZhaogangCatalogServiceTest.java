package com.itwray.iw.external.zhaogang;

import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanCatalog;
import com.itwray.iw.external.zhaogang.ZhaogangModels.PlanPageSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZhaogangCatalogServiceTest {

    private static final ZhaogangSession SESSION = new ZhaogangSession(
            "test-token", 183478L, "tester", "", "g-iijw5014");

    private ZhaogangCatalogService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void catalogLoadsMetadataWithoutFetchingLatestBuilds() {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());

        PlanCatalog catalog = service.catalog(SESSION);

        assertThat(catalog.plans()).hasSize(1);
        assertThat(catalog.plans().get(0).latestBuild()).isNull();
        assertThat(catalog.lastSyncedAt()).isEmpty();
        verify(coding, never()).latestBuild("test-token", 450L, 6196835L);
    }

    @Test
    void syncPageFetchesOnlyRequestedPlans() {
        CodingOpenApiPort coding = codingWithPlans(
                plan(6196835L, "online.base.service"), plan(6196836L, "online.order.service"));
        when(coding.latestBuild("test-token", 450L, 6196836L)).thenReturn(build(101L, "101"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());
        service.catalog(SESSION);

        PlanPageSync result = service.syncPage(SESSION, page(false, 6196836L));

        assertThat(result.plans()).extracting(plan -> plan.id()).containsExactly(6196836L);
        assertThat(result.plans().get(0).latestBuild().number()).isEqualTo("101");
        assertThat(result.lastSyncedAt()).isNotBlank();
        verify(coding, never()).latestBuild("test-token", 450L, 6196835L);
        verify(coding).latestBuild("test-token", 450L, 6196836L);
    }

    @Test
    void recentNonForceSyncUsesCacheAndForceSyncFetchesAgain() {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        when(coding.latestBuild("test-token", 450L, 6196835L))
                .thenReturn(build(101L, "101"), build(102L, "102"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());
        service.catalog(SESSION);

        PlanPageSync first = service.syncPage(SESSION, page(false, 6196835L));
        PlanPageSync cached = service.syncPage(SESSION, page(false, 6196835L));
        PlanPageSync forced = service.syncPage(SESSION, page(true, 6196835L));

        assertThat(first.plans().get(0).latestBuild().number()).isEqualTo("101");
        assertThat(cached.plans().get(0).latestBuild().number()).isEqualTo("101");
        assertThat(forced.plans().get(0).latestBuild().number()).isEqualTo("102");
        verify(coding, times(2)).latestBuild("test-token", 450L, 6196835L);
    }

    @Test
    void failedRefreshKeepsPreviousBuildAndThrottlesFailure() {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        when(coding.latestBuild("test-token", 450L, 6196835L))
                .thenReturn(build(101L, "101"))
                .thenThrow(new CodingOpenApiException("rate limited"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());
        service.catalog(SESSION);
        service.syncPage(SESSION, page(false, 6196835L));

        PlanPageSync failed = service.syncPage(SESSION, page(true, 6196835L));
        PlanPageSync throttled = service.syncPage(SESSION, page(false, 6196835L));

        assertThat(failed.plans().get(0).latestBuild().number()).isEqualTo("101");
        assertThat(failed.failedProjectIds()).containsExactly(450L);
        assertThat(throttled.failedProjectIds()).containsExactly(450L);
        assertThat(throttled.lastSyncedAt()).isNotBlank();
        verify(coding, times(2)).latestBuild("test-token", 450L, 6196835L);
    }

    @Test
    void catalogSurfacesPlanPermissionFailure() {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        when(coding.plans("test-token", 450L)).thenThrow(new CodingOpenApiException(
                "DescribeCodingCIJobs", "UnauthorizedOperation", "permission denied"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());

        assertThatThrownBy(() -> service.catalog(SESSION))
                .isInstanceOf(CodingOpenApiException.class)
                .satisfies(error -> assertThat(((CodingOpenApiException) error).requiredPermissions())
                        .containsExactly("持续集成任务（只读）"));
    }

    @Test
    void pageSyncSurfacesBuildPermissionFailure() {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        when(coding.latestBuild("test-token", 450L, 6196835L)).thenThrow(new CodingOpenApiException(
                "DescribeCodingCIBuilds", "UnauthorizedOperation", "permission denied"));
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());
        service.catalog(SESSION);

        assertThatThrownBy(() -> service.syncPage(SESSION, page(true, 6196835L)))
                .isInstanceOf(CodingOpenApiException.class)
                .satisfies(error -> assertThat(((CodingOpenApiException) error).requiredPermissions())
                        .containsExactly("持续集成构建（读写）"));
    }

    @Test
    void concurrentSyncsForSamePlanShareOneRequest() throws Exception {
        CodingOpenApiPort coding = codingWithPlans(plan(6196835L, "online.base.service"));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowRequest = new CountDownLatch(1);
        when(coding.latestBuild("test-token", 450L, 6196835L)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            requestStarted.countDown();
            allowRequest.await(2, TimeUnit.SECONDS);
            return build(101L, "101");
        });
        service = new ZhaogangCatalogService(coding, new ZhaogangProperties());
        service.catalog(SESSION);

        CompletableFuture<PlanPageSync> first = CompletableFuture.supplyAsync(
                () -> service.syncPage(SESSION, page(true, 6196835L)));
        assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> release = CompletableFuture.runAsync(allowRequest::countDown,
                CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
        PlanPageSync second = service.syncPage(SESSION, page(true, 6196835L));

        assertThat(first.join().plans().get(0).latestBuild().number()).isEqualTo("101");
        assertThat(second.plans().get(0).latestBuild().number()).isEqualTo("101");
        release.join();
        assertThat(calls).hasValue(1);
    }

    private CodingOpenApiPort codingWithPlans(CodingPlan... plans) {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        CodingProject project = new CodingProject(450L, "ops", "ops-云仓");
        when(coding.projects("test-token", 183478L)).thenReturn(List.of(project));
        when(coding.plans("test-token", 450L)).thenReturn(List.of(plans));
        return coding;
    }

    private CodingPlan plan(long id, String name) {
        return new CodingPlan(id, name, 450L, 1L, "CODING", "master",
                List.of("sit", "uat", "prd"), true, List.of(), null);
    }

    private CodingBuild build(long id, String number) {
        return new CodingBuild(id, number, "SUCCEED", "", "master", "6866f42", "tester",
                "1m", "2026-08-24 10:00:00");
    }

    private ZhaogangPlanPageSyncDto page(boolean force, long... jobIds) {
        ZhaogangPlanPageSyncDto dto = new ZhaogangPlanPageSyncDto();
        dto.setForce(force);
        dto.setPlans(java.util.Arrays.stream(jobIds).mapToObj(jobId -> {
            ZhaogangPlanPageSyncDto.PlanRef ref = new ZhaogangPlanPageSyncDto.PlanRef();
            ref.setProjectId(450L);
            ref.setJobId(jobId);
            return ref;
        }).toList());
        return dto;
    }
}
