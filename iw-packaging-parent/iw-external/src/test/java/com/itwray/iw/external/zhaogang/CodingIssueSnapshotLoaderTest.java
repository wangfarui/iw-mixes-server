package com.itwray.iw.external.zhaogang;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodingIssueSnapshotLoaderTest {

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
            loader.shutdown();
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
