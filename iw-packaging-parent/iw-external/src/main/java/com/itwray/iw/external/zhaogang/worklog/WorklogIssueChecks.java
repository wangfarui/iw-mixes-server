package com.itwray.iw.external.zhaogang.worklog;

import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.CheckedIssue;
import com.itwray.iw.external.zhaogang.CodingIssueSnapshotLoader.IssueKey;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.Issue;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.worklog.WorklogModels.IssueUpdate;
import com.itwray.iw.external.zhaogang.worklog.WorklogModule.Context;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
class WorklogIssueChecks {

    private static final int MAX_PENDING = 64;
    private static final int MAX_COMPLETED = 128;

    record Candidate(Set<Long> issueIds, List<Integer> rows) {
        Candidate() {
            this(new LinkedHashSet<>(), new ArrayList<>());
        }
    }

    record Submission(String id, boolean incomplete) {
    }

    private record Owner(long teamId, long userId, String fingerprint, Long workbenchTeamId) {
        static Owner of(Context context, Long workbenchTeamId) {
            return new Owner(context.codingTeamId(), context.userId(),
                    CodingCredentialService.fingerprint(context.token()), workbenchTeamId);
        }
    }

    private record RetryKey(String fingerprint, IssueKey issue) {
    }

    private record BatchKey(Owner owner, String teamHost, Map<String, Map<IssueKey, Candidate>> candidates) {
    }

    record Progress(boolean pending, List<IssueUpdate> updates, boolean incomplete) {
    }

    private record LookupRequest(String token, IssueKey key, Candidate candidate) {
    }

    private record Outcome(IssueKey key, Set<Long> ids, CheckedIssue checked) {
    }

    private static final class Job {
        final Owner owner;
        final String id = UUID.randomUUID().toString();
        final List<IssueUpdate> updates = new ArrayList<>();
        boolean incomplete;
        Instant completedAt;

        Job(Owner owner) {
            this.owner = owner;
        }

        synchronized Progress progress() {
            return new Progress(completedAt == null, List.copyOf(updates), incomplete);
        }

        synchronized void add(List<IssueUpdate> batch) {
            updates.addAll(batch);
        }

        synchronized void markIncomplete() {
            incomplete = true;
        }

        synchronized void finish(Instant time) {
            completedAt = time;
        }
    }

    private final WorklogDeletedIssues deletedIssues;
    private final CodingIssueSnapshotLoader loader;
    private final CodingIssueLinkBuilder links;
    private final Map<String, Job> pending = new HashMap<>();
    private final Map<String, Job> completed = new LinkedHashMap<>();
    private final Map<BatchKey, Job> batches = new HashMap<>();
    private final Map<RetryKey, Instant> retryAfter = new LinkedHashMap<>();
    // Only submit lookups and persist results here. Network waits stay in the shared loader.
    private final ScheduledExecutorService coordinator = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "zhaogang-worklog-issue-check");
        thread.setDaemon(true);
        return thread;
    });

    WorklogIssueChecks(WorklogDeletedIssues deletedIssues, CodingIssueSnapshotLoader loader, CodingIssueLinkBuilder links) {
        this.deletedIssues = deletedIssues;
        this.loader = loader;
        this.links = links;
    }

    synchronized Submission enqueue(Context context, Long workbenchTeamId,
                                    Map<String, Map<IssueKey, Candidate>> candidates, String teamHost, boolean refresh) {
        if (candidates.isEmpty() || coordinator.isShutdown()) return new Submission(null, false);
        Owner owner = Owner.of(context, workbenchTeamId);
        Map<String, Map<IssueKey, Candidate>> missing = new LinkedHashMap<>();
        Map<String, Map<IssueKey, Candidate>> fingerprints = new HashMap<>();
        Instant now = Instant.now();
        boolean incomplete = false;
        for (var entry : candidates.entrySet()) {
            String token = entry.getKey();
            String fingerprint = CodingCredentialService.fingerprint(token);
            for (var item : entry.getValue().entrySet()) {
                IssueKey issue = item.getKey();
                Candidate candidate = item.getValue();
                var checked = loader.cachedWorklogIssue(token, issue);
                if (refresh || checked == null || checked.lookup().missing() && !candidate.issueIds().isEmpty()) {
                    if (now.isBefore(retryAfter.getOrDefault(new RetryKey(fingerprint, issue), Instant.EPOCH))) {
                        incomplete = true;
                        continue;
                    }
                    missing.computeIfAbsent(token, ignored -> new LinkedHashMap<>()).put(issue, candidate);
                    fingerprints.computeIfAbsent(fingerprint, ignored -> new LinkedHashMap<>()).put(issue, candidate);
                }
            }
        }
        if (missing.isEmpty()) return new Submission(null, incomplete);
        var key = new BatchKey(owner, teamHost, fingerprints);
        Job running = batches.get(key);
        if (running != null) return new Submission(running.id, incomplete);
        if (pending.size() >= MAX_PENDING) {
            log.debug("工时事项查验任务已满，pending={}", pending.size());
            return new Submission(null, true);
        }
        Job job = new Job(owner);
        if (incomplete) job.markIncomplete();
        pending.put(job.id, job);
        batches.put(key, job);
        var requests = new ArrayDeque<LookupRequest>();
        missing.forEach((token, issues) -> issues.forEach((issue, candidate) ->
                requests.addLast(new LookupRequest(token, issue, candidate))));
        long admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        coordinator.execute(() -> submit(key, job, requests, refresh, new ArrayList<>(), admissionDeadline));
        return new Submission(job.id, incomplete);
    }

    synchronized Progress progress(Context context, Long workbenchTeamId, String id) {
        Job job = id == null ? null : pending.get(id);
        if (job == null && id != null) job = completed.get(id);
        if (job == null || !job.owner.equals(Owner.of(context, workbenchTeamId))) {
            return new Progress(false, List.of(), false);
        }
        if (job.completedAt != null && job.completedAt.plusSeconds(120).isBefore(Instant.now())) {
            completed.remove(id);
            return new Progress(false, List.of(), true);
        }
        return job.progress();
    }

    Issue cachedIssue(String token, IssueKey issue) {
        var checked = loader.cachedWorklogIssue(token, issue);
        return checked == null ? null : checked.lookup().issue();
    }

    private void submit(BatchKey batch, Job job, ArrayDeque<LookupRequest> waiting, boolean refresh,
                        List<CompletableFuture<Outcome>> outcomes, long admissionDeadline) {
        try {
            boolean foreground = batch.teamHost() != null;
            // Other jobs can continue submitting while this batch waits for loader capacity.
            for (int left = Math.min(waiting.size(), 512); left > 0; left--) {
                LookupRequest lookup = waiting.removeFirst();
                var request = loader.offerWorklogIssue(lookup.token(), lookup.key(), refresh, foreground);
                if (request == null) {
                    waiting.addLast(lookup);
                } else {
                    outcomes.add(request.handle((checked, failure) -> checked(batch, job, lookup, checked, failure)));
                }
            }
            if (!waiting.isEmpty() && admissionDeadline - System.nanoTime() > 0) {
                coordinator.schedule(() -> submit(batch, job, waiting, refresh, outcomes, admissionDeadline),
                        250, TimeUnit.MILLISECONDS);
                return;
            }
            collect(batch, job, outcomes, waiting.isEmpty());
        } catch (RuntimeException error) {
            job.markIncomplete();
            finish(batch, job);
            log.warn("工时事项后台调度失败，类型={}", error.getClass().getSimpleName());
        }
    }

    private Outcome checked(BatchKey batch, Job job, LookupRequest lookup, CheckedIssue checked, Throwable failure) {
        if (failure != null) {
            job.markIncomplete();
        } else if (checked.lookup().issue() != null) {
            if (!lookup.candidate().rows().isEmpty()) {
                job.add(updates(batch.teamHost(), lookup.key(), lookup.candidate().rows(), checked.lookup().issue()));
            }
        } else if (!checked.lookup().missing()) {
            job.markIncomplete();
            defer(lookup.token(), lookup.key());
        }
        return new Outcome(lookup.key(), lookup.candidate().issueIds(), checked);
    }

    private void collect(BatchKey batch, Job job, List<CompletableFuture<Outcome>> outcomes, boolean fullyScheduled) {
        if (!fullyScheduled) job.markIncomplete();
        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, failure) -> {
            try {
                if (failure == null && fullyScheduled) {
                    persistDeleted(batch.owner().teamId(), outcomes);
                } else {
                    job.markIncomplete();
                }
            } catch (RuntimeException error) {
                job.markIncomplete();
                log.warn("工时事项后台查验失败，类型={}", error.getClass().getSimpleName());
            } finally {
                finish(batch, job);
            }
        }, coordinator);
    }

    private List<IssueUpdate> updates(String teamHost, IssueKey key, List<Integer> rows, Issue issue) {
        var detail = new WorklogModels.Issue(key.issueCode(), issue.type(),
                StringUtils.defaultIfBlank(issue.typeName(), issue.type()), issue.title());
        var project = new WorklogModels.Project(key.projectName(),
                StringUtils.defaultIfBlank(issue.projectDisplayName(), key.projectName()));
        String url = links.build(teamHost, key.projectName(), key.issueCode(), issue);
        return rows.stream().map(row -> new IssueUpdate(row, detail, project, url)).toList();
    }

    private void persistDeleted(long teamId, List<CompletableFuture<Outcome>> outcomes) {
        Map<Long, IssueKey> confirmed = new LinkedHashMap<>();
        Set<Long> existing = new LinkedHashSet<>();
        for (var outcome : outcomes) {
            Outcome result = outcome.join();
            if (result.checked() == null) continue;
            var lookup = result.checked().lookup();
            if (lookup.missing() && result.ids().size() == 1) {
                confirmed.put(result.ids().iterator().next(), result.key());
            }
            if (lookup.issue() != null && lookup.issue().id() > 0) {
                existing.add(lookup.issue().id());
            }
        }
        confirmed.keySet().removeAll(existing);
        deletedIssues.record(teamId, confirmed);
    }

    private synchronized void finish(BatchKey batch, Job job) {
        if (coordinator.isShutdown()) return;
        Instant now = Instant.now();
        job.finish(now);
        batches.remove(batch);
        pending.remove(job.id);
        completed.values().removeIf(existing -> existing.completedAt.plusSeconds(120).isBefore(now));
        completed.put(job.id, job);
        if (completed.size() > MAX_COMPLETED) completed.remove(completed.keySet().iterator().next());
    }

    private synchronized void defer(String token, IssueKey issue) {
        retryAfter.put(new RetryKey(CodingCredentialService.fingerprint(token), issue), Instant.now().plusSeconds(30));
        if (retryAfter.size() > 5000) retryAfter.remove(retryAfter.keySet().iterator().next());
    }

    @PreDestroy
    synchronized void stop() {
        coordinator.shutdownNow();
        pending.clear();
        completed.clear();
        batches.clear();
    }
}
