package com.itwray.iw.external.zhaogang;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingIssueSnapshotLoaderTest {

    @Test
    void successfulCodingEditMustNotLeaveOldTitleInSnapshot() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCodingIssueCacheSeconds(30);
        AtomicReference<String> title = new AtomicReference<>("编辑前");
        when(coding.issue(anyString(), eq("project-a"), eq(1L))).thenAnswer(invocation ->
                new CodingOpenApiPort.Issue(1L, "SUB_TASK", "子工作项", title.get(), "project-a", true));
        CodingIssueSnapshotLoader loader = new CodingIssueSnapshotLoader(coding, properties);
        try {
            var key = new CodingIssueSnapshotLoader.IssueKey("project-a", 1L);
            assertThat(loader.load("token", List.of(key)).get(key).issue().title()).isEqualTo("编辑前");
            assertThat(loader.load("another-token", List.of(key)).get(key).issue().title()).isEqualTo("编辑前");
            title.set("编辑后");
            loader.invalidate("project-a", 1L);
            assertThat(loader.load("token", List.of(key)).get(key).issue().title()).isEqualTo("编辑后");
            assertThat(loader.load("another-token", List.of(key)).get(key).issue().title()).isEqualTo("编辑后");
        } finally {
            loader.close();
        }
    }

    @Test
    void deduplicatesAndLoadsIssueSnapshotsInParallel() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCodingIssueExecutorConcurrency(4);
        properties.setCodingIssueCacheSeconds(30);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(coding.issue("token", "project-a", 1L)).thenAnswer(invocation -> issue(active, maxActive, 1L));
        when(coding.issue("token", "project-a", 2L)).thenAnswer(invocation -> issue(active, maxActive, 2L));
        CodingIssueSnapshotLoader loader = new CodingIssueSnapshotLoader(coding, properties);
        try {
            var result = loader.load("token", List.of(
                    new CodingIssueSnapshotLoader.IssueKey("project-a", 1L),
                    new CodingIssueSnapshotLoader.IssueKey("project-a", 1L),
                    new CodingIssueSnapshotLoader.IssueKey("project-a", 2L)));
            assertThat(result).hasSize(2);
            assertThat(result.values()).allMatch(item -> item.issue() != null);
            assertThat(maxActive.get()).isGreaterThanOrEqualTo(2);
        } finally {
            loader.close();
        }
    }

    @Test
    void startsAnotherTokenWhileTheFirstTokenHasQueuedIssues() throws Exception {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCodingIssueExecutorConcurrency(2);
        properties.setCodingConcurrencyPerToken(1);
        var firstStarted = new CountDownLatch(1);
        var otherStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(coding.issue(eq("first"), eq("project"), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            firstStarted.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("First token was not released");
            return null;
        });
        when(coding.issue("other", "project", 1L)).thenAnswer(call -> {
            otherStarted.countDown();
            return null;
        });
        var first = new CodingIssueSnapshotLoader.IssueKey("project", 1L);
        var second = new CodingIssueSnapshotLoader.IssueKey("project", 2L);
        var executor = Executors.newFixedThreadPool(2);
        try (var loader = new CodingIssueSnapshotLoader(coding, properties)) {
            var slow = executor.submit(() -> loader.load("first", List.of(first, second)));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var fast = executor.submit(() -> loader.load("other", List.of(first)));
            assertThat(otherStarted.await(2, TimeUnit.SECONDS)).isTrue();
            verify(coding, times(0)).issue("first", "project", 2L);
            release.countDown();
            assertThat(fast.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(slow.get(5, TimeUnit.SECONDS)).hasSize(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void worklogCachesConfirmedFactsUntilTheOriginalCheckExpires() {
        CodingOpenApiPort coding = mock(CodingOpenApiPort.class);
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setWorklogIssueDeletionDelaySeconds(300);
        var time = new AtomicReference<>(Instant.parse("2026-11-13T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return time.get();
            }
        };
        var key = new CodingIssueSnapshotLoader.IssueKey("project", 1L);
        var issue = new CodingOpenApiPort.Issue(1L, "REQUIREMENT", "需求", "事项", "project", false);
        var missing = new CodingOpenApiException("DescribeIssue", "InvalidParameterValue", "issue_not_exist");
        var denied = new CodingOpenApiException("DescribeIssue", "UnauthorizedOperation", "permission denied");
        when(coding.issue("token", "project", 1L)).thenReturn(issue).thenThrow(missing);
        when(coding.issue("other", "project", 1L)).thenThrow(denied).thenReturn(issue);
        try (var loader = new CodingIssueSnapshotLoader(coding, properties, clock)) {
            var batch = Map.of("token", List.of(key), "other", List.of(key));
            var initial = loader.loadForWorklogs(batch, false);
            assertThat(initial.get("token").get(key).lookup().issue()).isSameAs(issue);
            assertThat(initial.get("other").get(key).lookup().missing()).isFalse();

            time.updateAndGet(now -> now.plusSeconds(299));
            var warm = loader.loadForWorklogs(batch, false);
            assertThat(warm.get("token").get(key)).isSameAs(initial.get("token").get(key));
            assertThat(warm.get("other").get(key).lookup().issue()).isSameAs(issue);
            verify(coding).issue("token", "project", 1L);
            verify(coding, times(2)).issue("other", "project", 1L);

            time.updateAndGet(now -> now.plusSeconds(1));
            var deleted = loader.loadForWorklogs(batch, false);
            assertThat(deleted.get("token").get(key).lookup().missing()).isTrue();
            assertThat(loader.loadForWorklogs(batch, false).get("token").get(key))
                    .isSameAs(deleted.get("token").get(key));
            verify(coding, times(2)).issue("token", "project", 1L);

            loader.loadForWorklogs(batch, true);
            verify(coding, times(3)).issue("token", "project", 1L);
        }
    }

    private CodingOpenApiPort.Issue issue(AtomicInteger active, AtomicInteger maxActive, long code)
            throws InterruptedException {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
        try {
            Thread.sleep(100);
            return new CodingOpenApiPort.Issue(code, "REQUIREMENT", "需求",
                    "事项 " + code, "project-a", false);
        } finally {
            active.decrementAndGet();
        }
    }
}
