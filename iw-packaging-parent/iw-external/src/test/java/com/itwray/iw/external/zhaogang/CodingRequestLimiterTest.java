package com.itwray.iw.external.zhaogang;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingRequestLimiterTest {

    @Test
    void teamRateIsSharedAcrossTokensAndActionsKeepSeparateBudgets() throws Exception {
        var properties = new ZhaogangProperties();
        properties.setCodingConcurrencyPerToken(1);
        properties.setCodingIssueQpsPerTeam(1);
        properties.setCodingWorklogQpsPerTeam(1);
        var limiter = new CodingRequestLimiter(properties);
        limiter.acquire("admin", CodingRequestLimiter.ISSUE_ACTION);
        limiter.release("admin");

        assertThatThrownBy(() -> limiter.acquire("personal", CodingRequestLimiter.ISSUE_ACTION,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)))
                .isInstanceOfSatisfying(CodingOpenApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CODING_ADMISSION_TIMEOUT"));

        limiter.acquire("personal", CodingRequestLimiter.WORKLOG_ACTION,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
        limiter.release("personal");
        assertThatThrownBy(() -> limiter.acquire("admin", CodingRequestLimiter.WORKLOG_ACTION,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)))
                .isInstanceOf(CodingOpenApiException.class);
    }

    @Test
    void differentTokensDoNotShareConcurrencyPermit() throws Exception {
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCodingConcurrencyPerToken(1);
        properties.setCodingIssueQpsPerToken(0);
        CodingRequestLimiter limiter = new CodingRequestLimiter(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch tokenAStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                limiter.acquire("token-a", "DescribeIssue");
                tokenAStarted.countDown();
                releaseA.await();
                limiter.release("token-a");
                return null;
            });
            assertThat(tokenAStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var tokenB = executor.submit(() -> {
                limiter.acquire("token-b", "DescribeIssue");
                limiter.release("token-b");
                return true;
            });
            assertThat(tokenB.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseA.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameTokenStillRespectsConfiguredConcurrency() throws Exception {
        ZhaogangProperties properties = new ZhaogangProperties();
        properties.setCodingConcurrencyPerToken(1);
        properties.setCodingIssueQpsPerToken(0);
        CodingRequestLimiter limiter = new CodingRequestLimiter(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                limiter.acquire("same-token", "DescribeIssue");
                firstStarted.countDown();
                releaseFirst.await();
                limiter.release("same-token");
                return null;
            });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                limiter.acquire("same-token", "DescribeIssue");
                limiter.release("same-token");
                return true;
            });
            Thread.sleep(100);
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();
            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
}
