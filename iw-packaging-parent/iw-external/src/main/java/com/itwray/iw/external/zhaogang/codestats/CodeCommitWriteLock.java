package com.itwray.iw.external.zhaogang.codestats;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes code mirror writes in the single iw-external process. */
final class CodeCommitWriteLock {
    private static final ReentrantLock LOCK = new ReentrantLock(true);

    private CodeCommitWriteLock() {
    }

    static void run(Runnable write) throws InterruptedException {
        // Callers have no surrounding transaction, so the mapper commits before returning.
        LOCK.lockInterruptibly();
        try {
            write.run();
        } finally {
            LOCK.unlock();
        }
    }
}
