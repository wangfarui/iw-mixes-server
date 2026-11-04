package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CodeScanReadAheadTest {
    @Test
    void startsFourIndependentReadsBeforeAwaitingResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (CodeScanReadAhead<CodingCommitPage> reads = new CodeScanReadAhead<>(4)) {
            Callable<CodingCommitPage> read = () -> {
                entered.countDown();
                release.await();
                return new CodingCommitPage(List.of(), 0, 1, 100);
            };
            var results = reads.submit(List.of(read, read, read, read));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS), "分支读取不能串行等待");
                assertEquals(4, results.size());
            } finally {
                release.countDown();
            }
            for (var result : results) assertNotNull(result.get(3, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> reads.submit(List.of(read, read, read, read, read)));
        }
    }

    @Test
    void closingCancelsOutstandingReads() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CodeScanReadAhead<CodingCommitPage> reads = new CodeScanReadAhead<>(1);
        var pending = reads.submit(List.of(() -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return new CodingCommitPage(List.of(), 0, 1, 100);
        }));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally {
            reads.close();
        }
        assertTrue(pending.get(0).isCancelled());
    }
}
