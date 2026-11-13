package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.Context;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
class WorklogIssueChecks {

    private record Owner(long teamId, long userId, String fingerprint, Long workbenchTeamId) {
        static Owner of(Context context, Long workbenchTeamId) {
            return new Owner(context.codingTeamId(), context.userId(),
                    CodingCredentialService.fingerprint(context.token()), workbenchTeamId);
        }
    }

    private record RetryKey(String fingerprint, IssueKey issue) {
    }

    private record BatchKey(Owner owner, Map<String, Map<IssueKey, Set<Long>>> candidates) {
    }

    private final WorklogDeletedIssues deletedIssues;
    private final CodingIssueSnapshotLoader loader;
    private final Map<String, Owner> pending = new ConcurrentHashMap<>();
    private final Map<BatchKey, String> batches = new HashMap<>();
    private final Map<RetryKey, Instant> retryAfter = new LinkedHashMap<>();
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(16);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, queue, runnable -> {
        Thread thread = new Thread(runnable, "zhaogang-worklog-issue-check");
        thread.setDaemon(true);
        return thread;
    });

    WorklogIssueChecks(WorklogDeletedIssues deletedIssues, CodingIssueSnapshotLoader loader) {
        this.deletedIssues = deletedIssues;
        this.loader = loader;
        executor.prestartAllCoreThreads();
    }

    synchronized String enqueue(Context context, Long workbenchTeamId,
                                Map<String, Map<IssueKey, Set<Long>>> candidates, boolean refresh) {
        if (candidates.isEmpty() || executor.isShutdown()) return null;
        Owner owner = Owner.of(context, workbenchTeamId);
        Map<String, Map<IssueKey, Set<Long>>> missing = new LinkedHashMap<>();
        Instant now = Instant.now();
        candidates.forEach((token, issues) -> {
            String fingerprint = CodingCredentialService.fingerprint(token);
            issues.forEach((issue, ids) -> {
                var checked = loader.cachedWorklogIssue(token, issue);
                if (now.isBefore(retryAfter.getOrDefault(new RetryKey(fingerprint, issue), Instant.EPOCH))) return;
                if (refresh || checked == null || checked.lookup().missing() && !ids.isEmpty()) {
                    missing.computeIfAbsent(token, ignored -> new LinkedHashMap<>()).put(issue, ids);
                }
            });
        });
        if (missing.isEmpty()) return null;
        // 活跃任务按 PAT 指纹去重，原始 PAT 只存在于待处理任务内。
        Map<String, Map<IssueKey, Set<Long>>> fingerprints = new HashMap<>();
        missing.forEach((token, issues) -> fingerprints.put(CodingCredentialService.fingerprint(token), issues));
        BatchKey key = new BatchKey(owner, fingerprints);
        String running = batches.get(key);
        if (running != null) return running;
        String id = UUID.randomUUID().toString();
        pending.put(id, owner);
        batches.put(key, id);
        Runnable task = () -> {
            try {
                check(context.codingTeamId(), missing, refresh);
            } catch (RuntimeException error) {
                log.warn("工时事项后台查验失败，类型={}", error.getClass().getSimpleName());
            } finally {
                synchronized (this) {
                    batches.remove(key);
                    pending.remove(id);
                }
            }
        };
        if (refresh ? queue.offerFirst(task) : queue.offerLast(task)) return id;
        batches.remove(key);
        pending.remove(id);
        return null;
    }

    boolean isPending(Context context, Long workbenchTeamId, String checkId) {
        return checkId != null && Owner.of(context, workbenchTeamId).equals(pending.get(checkId));
    }

    Issue cachedIssue(String token, IssueKey issue) {
        var checked = loader.cachedWorklogIssue(token, issue);
        return checked == null ? null : checked.lookup().issue();
    }

    private void check(long teamId, Map<String, Map<IssueKey, Set<Long>>> candidates, boolean refresh) {
        Map<String, Collection<IssueKey>> requests = new LinkedHashMap<>();
        Map<Long, IssueKey> confirmed = new LinkedHashMap<>();
        Set<Long> existing = new LinkedHashSet<>();
        Set<Long> ids = new LinkedHashSet<>();
        candidates.values().forEach(issues -> issues.values().forEach(ids::addAll));
        Set<Long> alreadyDeleted = deletedIssues.find(teamId, ids);
        candidates.forEach((token, issues) -> {
            List<IssueKey> keys = new ArrayList<>();
            issues.forEach((key, issueIds) -> {
                if (issueIds.isEmpty() || !alreadyDeleted.containsAll(issueIds)) keys.add(key);
            });
            if (!keys.isEmpty()) requests.put(token, keys);
        });
        loader.loadForWorklogs(requests, refresh).forEach((token, issues) -> issues.forEach((key, checked) -> {
            Set<Long> issueIds = candidates.get(token).get(key);
            if (checked.lookup().missing() && issueIds.size() == 1) {
                confirmed.put(issueIds.iterator().next(), key);
            }
            Issue issue = checked.lookup().issue();
            if (issue != null && issue.id() > 0) existing.add(issue.id());
            if (issue == null && !checked.lookup().missing()) defer(token, key);
        }));
        confirmed.keySet().removeAll(existing);
        deletedIssues.record(teamId, confirmed);
    }

    private synchronized void defer(String token, IssueKey issue) {
        retryAfter.put(new RetryKey(CodingCredentialService.fingerprint(token), issue), Instant.now().plusSeconds(30));
        if (retryAfter.size() > 5000) retryAfter.remove(retryAfter.keySet().iterator().next());
    }

    @PreDestroy
    synchronized void stop() {
        executor.shutdownNow();
        pending.clear();
        batches.clear();
    }
}
