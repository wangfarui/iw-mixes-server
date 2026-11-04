package com.itwray.iw.external.zhaogang.codestats;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 每批至多四个分支提交读取，结果仍由扫描线程依次聚合和落库。 */
final class CodeScanReadAhead<T> implements AutoCloseable {
    private final int width;
    private final ExecutorService executor;
    private List<Future<T>> pending = List.of();

    CodeScanReadAhead(int concurrency) {
        width = Math.max(1, Math.min(4, concurrency));
        executor = Executors.newFixedThreadPool(width, task -> {
            Thread thread = new Thread(task, "zhaogang-code-read-ahead");
            thread.setDaemon(true);
            return thread;
        });
    }

    int width() { return width; }

    List<Future<T>> submit(List<Callable<T>> batch) {
        if (batch.size() > width) throw new IllegalArgumentException("分支预取超出并发上限");
        pending.forEach(future -> future.cancel(true));
        pending = batch.stream().map(executor::submit).toList();
        return pending;
    }

    @Override
    public void close() {
        pending.forEach(future -> future.cancel(true));
        executor.shutdownNow();
    }
}
