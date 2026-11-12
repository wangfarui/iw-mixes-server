package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.mapper.ZhaogangCodeCommitMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanJobMapper;
import com.itwray.iw.external.mapper.ZhaogangCodeScanRepoMapper;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingUserProfile;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitDiff;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommit;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitQuery;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingDepot;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingDepotPage;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingBranchHead;
import com.itwray.iw.external.zhaogang.CodingResponseTooLargeException;
import com.itwray.iw.external.zhaogang.ZhaogangProperties;
import com.itwray.iw.external.zhaogang.credential.CodingCredentialService;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsModels.Scope;
import com.itwray.iw.external.zhaogang.codestats.CodeStatsViewCache.CachedProfile;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.CommitEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.IdentityEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanJobEntity;
import com.itwray.iw.external.zhaogang.codestats.entity.CodeStatsEntities.ScanRepoEntity;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 代码扫描工作线程：全量/增量/行数补齐共享同一任务队列和 active_key。 */
@Component
class CodeScanWorker {

    private static final Logger log = LoggerFactory.getLogger(CodeScanWorker.class);
    static final String DIFF_RETRY = "DIFF_RETRY";

    private final CodingOpenApiPort coding;
    private final ZhaogangCodeScanJobMapper jobMapper;
    private final ZhaogangCodeScanRepoMapper repoMapper;
    private final ZhaogangCodeCommitMapper commitMapper;
    private final CodeIdentityResolver identities;
    private final CodeStatsViewCache cache;
    private final ZhaogangProperties properties;
    private final Clock clock;
    private final CodeGitLineStats gitLineStats;
    private long nextDbCancelCheckMillis;

    @Autowired
    CodeScanWorker(CodingOpenApiPort coding, ZhaogangCodeScanJobMapper jobMapper,
                   ZhaogangCodeScanRepoMapper repoMapper, ZhaogangCodeCommitMapper commitMapper,
                   CodeIdentityResolver identities, CodeStatsViewCache cache, ZhaogangProperties properties,
                   CodeGitLineStats gitLineStats) {
        this(coding, jobMapper, repoMapper, commitMapper, identities, cache, properties,
                Clock.system(CodeStatsSupport.CHINA_ZONE), gitLineStats);
    }

    CodeScanWorker(CodingOpenApiPort coding, ZhaogangCodeScanJobMapper jobMapper,
                   ZhaogangCodeScanRepoMapper repoMapper, ZhaogangCodeCommitMapper commitMapper,
                   CodeIdentityResolver identities, CodeStatsViewCache cache, ZhaogangProperties properties,
                   Clock clock) {
        this(coding, jobMapper, repoMapper, commitMapper, identities, cache, properties, clock,
                new CodeGitLineStats(coding, properties));
    }

    CodeScanWorker(CodingOpenApiPort coding, ZhaogangCodeScanJobMapper jobMapper,
                   ZhaogangCodeScanRepoMapper repoMapper, ZhaogangCodeCommitMapper commitMapper,
                   CodeIdentityResolver identities, CodeStatsViewCache cache, ZhaogangProperties properties,
                   Clock clock, CodeGitLineStats gitLineStats) {
        this.coding = coding;
        this.jobMapper = jobMapper;
        this.repoMapper = repoMapper;
        this.commitMapper = commitMapper;
        this.identities = identities;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
        this.gitLineStats = gitLineStats;
    }

    void run(ScanInput input) {
        ScanJobEntity job = input.job();
        try {
            job.setStatus("RUNNING");
            job.setStartedAt(LocalDateTime.now(clock));
            boolean retryOnly = DIFF_RETRY.equals(input.mode());
            job.setStage(retryOnly || "DIFF_BACKFILL".equals(input.mode()) ? "DIFFS" : "DISCOVERING");
            save(job);
            if (cancelled(job)) {
                finish(job, "CANCELLED", "CANCELLED", "扫描已取消");
                return;
            }
            if (!retryOnly) {
                job.setStage("IDENTITIES");
                applyIdentities(input, job);
            }
            List<IdentityEntity> selection = identities.mappings(input.scope());
            input = new ScanInput(input.job(), input.scope(), input.month(), input.mode(), input.requestedBy(),
                    identities.mappingIndex(selection), identities.signature(selection), input.retryCommitIds());
            job.setIdentitySignature(input.identitySignature());
            if (!retryOnly && !"SYNC".equals(input.mode())) pruneUnmatched(input);
            save(job);
            Map<Long, DepotPlan> depots = Map.of();
            Map<Long, List<String>> depotTokens = Map.of();
            Set<String> touchedMonths = new LinkedHashSet<>();
            touchedMonths.add(input.month().toString());
            if ("SYNC".equals(input.mode())) {
                for (YearMonth m = YearMonth.from(job.getSyncFrom()); !m.isAfter(YearMonth.from(job.getSyncTo())); m = m.plusMonths(1))
                    touchedMonths.add(m.toString());
            }
            if (!retryOnly && !"DIFF_BACKFILL".equals(input.mode())) {
                depots = discover(input, job);
                Map<Long, List<BranchPlan>> branches = discoverBranches(input, job, depots);
                touchedMonths.addAll("SYNC".equals(input.mode())
                        ? collectContinuous(input, job, depots, branches)
                        : collectCommits(input, job, depots, branches));
            }
            fetchDiffs(input, job, depots, touchedMonths);
            aggregate(input, job);
        } catch (ScanCancelledException e) {
            finish(job, "CANCELLED", "CANCELLED", "扫描已取消");
        } catch (ScanTaskLostException e) {
            log.info("zhaogang code scan task no longer active, jobId={}", job.getId());
        } catch (CodingOpenApiException e) {
            finish(job, "FAILED", errorCode(e), safeMessage(e));
        } catch (RuntimeException e) {
            log.warn("zhaogang code scan failed, jobId={}, error={}", job.getId(), e.getClass().getSimpleName());
            finish(job, "FAILED", "SCAN_FAILED", "代码扫描失败，请稍后重试");
        }
    }

    private Map<Long, DepotPlan> discover(ScanInput input, ScanJobEntity job) {
        List<MemberToken> tokens = input.scope().members().stream()
                .filter(member -> StringUtils.isNotBlank(member.token()))
                .map(member -> new MemberToken(member.userId(), member.token()))
                .toList();
        if (tokens.isEmpty()) {
            throw new CodingOpenApiException("没有可用的 CODING 令牌，无法发现代码仓库");
        }
        Map<Long, DepotPlan> depots = new LinkedHashMap<>();
        int successfulTokens = 0;
        RuntimeException lastError = null;
        if (input.scope().members().stream().anyMatch(member -> StringUtils.isBlank(member.token()))) {
            job.setCoverageIncomplete(true);
        }
        try (CodeScanReadAhead<MemberDiscovery> reads = new CodeScanReadAhead<>(4)) {
            for (int offset=0; offset<tokens.size(); offset+=reads.width()) {
                List<MemberToken> batch=tokens.subList(offset, Math.min(tokens.size(), offset+reads.width()));
                var pending=reads.submit(batch.stream().<java.util.concurrent.Callable<MemberDiscovery>>map(token ->
                        () -> discoverMember(token)).toList());
                for (int index=0; index<batch.size(); index++) {
                    if (cancelled(job)) throw new ScanCancelledException();
                    MemberDiscovery result;
                    try { result=pending.get(index).get(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ScanCancelledException(); }
                    catch (java.util.concurrent.ExecutionException e) { throw new CodingOpenApiException("仓库发现失败"); }
                    for (CodingDepot depot : result.depots()) {
                        depots.computeIfAbsent(depot.id(), ignored -> new DepotPlan(depot,new ArrayList<>()))
                                .tokens().add(batch.get(index));
                    }
                    if (result.error()==null) successfulTokens++;
                    else { lastError=result.error(); job.setCoverageIncomplete(true); }
                    save(job);
                }
            }
        }
        if (successfulTokens == 0 && depots.isEmpty()) {
            throw lastError instanceof CodingOpenApiException codingError
                    ? codingError : new CodingOpenApiException("代码仓库发现失败，请稍后重试");
        }
        job.setTotalRepos(depots.size());
        job.setDoneRepos(0);
        job.setStage("BRANCHES");
        save(job);
        for (DepotPlan plan : depots.values()) {
            ScanRepoEntity repo = new ScanRepoEntity();
            repo.setJobId(job.getId());
            repo.setProjectId(plan.depot().projectId());
            repo.setProjectName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().projectName()), 128));
            repo.setProjectDisplayName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().projectDisplayName()), 128));
            repo.setDepotId(plan.depot().id());
            repo.setDepotName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().name()), 128));
            repo.setWebUrl(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().webUrl()), 512));
            repo.setBranchCount(0);
            repo.setCommitCount(0);
            repo.setStatus("PENDING");
            repoMapper.upsert(repo);
        }
        return depots;
    }

    private MemberDiscovery discoverMember(MemberToken token) {
        List<CodingDepot> result=new ArrayList<>();
        try {
            int seen=0;
            for (int page=1; page<=100; page++) {
                CodingDepotPage value=coding.myDepots(token.token(),page,100);
                value.items().stream().filter(this::isGitDepot).forEach(result::add);
                seen+=value.items().size();
                if(value.items().size()<100 || (value.totalCount()>0 && seen>=value.totalCount()))
                    return new MemberDiscovery(result,null);
            }
            return new MemberDiscovery(result,new CodingOpenApiException("仓库目录达到分页上限"));
        } catch(RuntimeException failure) { return new MemberDiscovery(result,failure); }
    }

    private record MemberDiscovery(List<CodingDepot> depots, RuntimeException error) {}

    private boolean isGitDepot(CodingDepot depot) {
        String vcs = StringUtils.trimToEmpty(depot.vcsType()).toLowerCase(Locale.ROOT);
        String type = StringUtils.trimToEmpty(depot.depotType()).toUpperCase(Locale.ROOT);
        if (!vcs.isEmpty() && !vcs.contains("git")) return false;
        return type.isEmpty() || type.contains("GIT") || "CODING".equals(type);
    }

    private void applyIdentities(ScanInput input, ScanJobEntity job) {
        for (CodeStatsModule.MemberCredential member : input.scope().members()) {
            if (StringUtils.isBlank(member.token())) continue;
            try {
                identities.initializeDefaults(input.scope().codingTeamId(), member.userId(), profile(member));
            } catch (CodingOpenApiException failure) {
                if (input.scope().scope() == Scope.SELF) throw failure;
                job.setCoverageIncomplete(true);
            }
        }
        job.setStage("DISCOVERING");
        save(job);
    }

    private CachedProfile profile(CodeStatsModule.MemberCredential member) {
        String fingerprint = CodingCredentialService.fingerprint(member.token());
        CachedProfile cached = cache.profile(fingerprint).orElse(null);
        if (cached != null) return cached;
        try {
            CodingUserProfile profile = coding.currentUserProfile(member.token());
            CachedProfile value = new CachedProfile(profile.id(), profile.name(), profile.avatar(),
                    CodeStatsSupport.emailHash(profile.email()), profile.teamId(), System.currentTimeMillis());
            cache.putProfile(fingerprint, value);
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<Long, List<BranchPlan>> discoverBranches(ScanInput input, ScanJobEntity job,
                                                         Map<Long, DepotPlan> depots) {
        Map<Long, List<BranchPlan>> result = new LinkedHashMap<>();
        for (DepotPlan plan : depots.values()) {
            if (cancelled(job)) throw new ScanCancelledException();
            List<CodingBranchHead> branches = null;
            String chosenToken = null;
            RuntimeException lastError = null;
            for (MemberToken token : plan.tokens()) {
                try {
                    branches = coding.branchHeads(token.token(), plan.depot().projectId(), plan.depot().id(),
                            "CODING").stream().filter(branch -> StringUtils.isNotBlank(branch.name())).toList();
                    chosenToken = token.token();
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                }
            }
            if (branches == null) {
                String code = lastError instanceof CodingOpenApiException error && error.isPermissionDenied()
                        ? "PERMISSION_DENIED" : "REQUEST_FAILED";
                markRepoFailed(job, plan, code, "当前成员令牌均无该仓库读取权限");
                continue;
            }
            boolean truncated = branches.size() > properties.getCodeMaxBranchesPerRepo();
            if (truncated) {
                branches = branches.subList(0, properties.getCodeMaxBranchesPerRepo());
                job.setTruncated(true);
            }
            repoMapper.updateState(job.getId(), plan.depot().id(), "RUNNING", branches.size(), 0, null, null);
            String repoToken = chosenToken;
            result.put(plan.depot().id(), branches.stream()
                    .map(branch -> new BranchPlan(plan, repoToken, branch.name(), branch.sha(), branch.lastCommitAtMillis())).toList());
            job.setDoneRepos(job.getDoneRepos() + 1);
            job.setTotalBranches(job.getTotalBranches() + branches.size());

            save(job);
        }
        job.setStage("COMMITS");
        save(job);
        return result;
    }

    private Set<String> collectContinuous(ScanInput input, ScanJobEntity job, Map<Long, DepotPlan> depots,
                                          Map<Long, List<BranchPlan>> branches) {
        Set<String> touched = new LinkedHashSet<>();

        int skipped = 0;
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        for (var entry : branches.entrySet()) {
            if (cancelled(job)) throw new ScanCancelledException();
            DepotPlan depot = depots.get(entry.getKey());
            int callsBefore=calls.get();
            Map<String, CommitEntity> existing = new HashMap<>();
            commitMapper.selectSyncRepo(input.scope().codingTeamId(), input.scope().scope().name(), input.scope().scopeId(),
                    entry.getKey(), job.getSyncFrom(), job.getSyncTo()).forEach(row -> existing.put(row.getSha(), row));
            CodeBranchSync.Checkpoint before = null;
            ScanRepoEntity stored = repoMapper.selectSyncCheckpoint(input.scope().codingTeamId(), input.scope().scope().name(),
                    input.scope().scopeId(), entry.getKey(), input.identitySignature(), job.getSyncFrom(), job.getId());
            if (stored != null) {
                try { before = CodeSyncCheckpointCodec.decode(stored.getSyncState()); }
                catch (Exception invalid) { /* 无效游标安全回退，保留旧统计直到成功。 */ }
            }
            Map<String,CodeCommitGraph.Node> graph=CodeCommitGraph.initialize(before);
            Map<String, String> nextBranches = new LinkedHashMap<>();
            Map<String, CodeBranchSync.Head> nextHeads = new LinkedHashMap<>();
            Map<String, CommitAccumulator> commits = new LinkedHashMap<>();
            boolean failed = false;
            String failureCode = "REQUEST_FAILED";
            Map<String, List<BranchPlan>> groups = new LinkedHashMap<>();
            for (BranchPlan branch : entry.getValue()) groups.computeIfAbsent(
                    StringUtils.defaultIfBlank(branch.headSha(), "ref:"+branch.branch()), ignored -> new ArrayList<>()).add(branch);
            List<List<BranchPlan>> allGroups = new ArrayList<>(groups.values());
            try (CodeScanReadAhead<ContinuousRead> reads = new CodeScanReadAhead<>(properties.getCodingConcurrencyPerToken())) {
                for (int offset=0; offset<allGroups.size(); offset+=reads.width()) {
                    List<List<BranchPlan>> batch = allGroups.subList(offset, Math.min(allGroups.size(), offset+reads.width()));
                    CodeBranchSync.Checkpoint checkpoint = before;
                    Map<String,CodeCommitGraph.Node> knownGraph=Map.copyOf(graph);
                    Set<String> available=new HashSet<>(existing.keySet());available.addAll(commits.keySet());
                    var pending = reads.submit(batch.stream().<java.util.concurrent.Callable<ContinuousRead>>map(group ->
                            () -> readContinuousHead(input, job, group.get(0), checkpoint, existing, calls, knownGraph, available)).toList());
                    for (int i=0; i<batch.size(); i++) {
                        List<BranchPlan> group = batch.get(i);
                        try {
                            ContinuousRead result = awaitContinuous(pending.get(i), job);
                            result.learned().forEach((sha,node)->graph.merge(sha,node,(old,value)->old.boundary()?value:value.boundary()?old:value));
                            if (!result.result().complete()) {
                                failed=true;
                                if(result.result().errorCode()==null) job.setTruncated(true);
                                else failureCode=result.result().errorCode();
                            }
                            if (result.reused()) skipped+=group.size();
                            CodeBranchSync.Head current = result.result().head();
                            BranchPlan first=group.get(0);
                            for (CodingCommit value : result.result().added()) accumulate(commits, depot, first.branch(), value, input, job);
                            List<CommitEntity> reused = current.matched().stream().filter(sha -> !commits.containsKey(sha))
                                    .map(existing::get).filter(java.util.Objects::nonNull).toList();
                            mergeSnapshot(commits, group, new BranchSnapshot(reused), input, job);
                            for (String sha : current.matched()) {
                                CommitAccumulator item=commits.get(sha);
                                if (item!=null) group.forEach(branch -> item.addBranch(branch.branch()));
                            }
                            if(result.result().complete()) {
                                group.forEach(branch -> nextBranches.put(branch.branch(), branch.headSha()));
                                nextHeads.put(first.headSha(), current);
                            }
                        } catch (ScanCancelledException | ScanTaskLostException stopped) { throw stopped; }
                        catch (RuntimeException error) {
                            failed=true;
                            if(error instanceof CodingOpenApiException api && api.isPermissionDenied()) failureCode="PERMISSION_DENIED";
                        }
                        job.setDoneBranches(job.getDoneBranches()+group.size()); save(job);
                    }
                }
            }
            if (!java.util.Objects.equals(input.identitySignature(), identities.signature(input.scope())))
                throw new CodingOpenApiException("IDENTITY_CHANGED", "Git 身份已变化，将按新身份重新同步");
            // 单个 ref 变化不能使整个仓库的已验证分支进度失效。只发布仍稳定的分支；未完整范围不清旧行。
            int changedBranches = 0;
            try {
                Map<String, String> after = new LinkedHashMap<>();
                coding.branchHeads(entry.getValue().isEmpty() ? depot.tokens().get(0).token() : entry.getValue().get(0).token(),
                        depot.depot().projectId(), depot.depot().id(), "CODING")
                        .forEach(branch -> after.put(branch.name(), branch.sha()));
                if (!after.equals(nextBranches)) {
                    if (!failed) {
                        failureCode = "BRANCH_CHANGED";
                        changedBranches = logChangedBranches(job.getId(), entry.getKey(), nextBranches, after);
                    }
                    failed = true;
                    nextBranches.entrySet().removeIf(branch -> !java.util.Objects.equals(after.get(branch.getKey()),branch.getValue()));
                    nextHeads.keySet().retainAll(new HashSet<>(nextBranches.values()));
                }
            } catch (RuntimeException error) {
                failed = true;
                nextBranches.clear(); nextHeads.clear();
            }
            int written = upsertCommits(commits.values());
            commits.values().stream().map(row -> row.month.toString()).forEach(touched::add);
            job.setTotalCommits(job.getTotalCommits() + written);
            job.setDoneCommits(job.getDoneCommits() + written);
            // 先保存幂等提交，再发布独立验证过的分支游标。即使其他分支失败，下一轮也只重试缺口。
            if (!nextBranches.isEmpty() || !failed || !graph.isEmpty()) {
                try {
                    String encoded;
                    try { encoded=CodeSyncCheckpointCodec.encode(new CodeBranchSync.Checkpoint(nextBranches,nextHeads,graph)); }
                    catch(java.io.IOException oversized) {
                        // 大仓库降级到已验证头闭包；不因共享图过大丢失整个同步任务。
                        encoded=CodeSyncCheckpointCodec.encode(new CodeBranchSync.Checkpoint(nextBranches,nextHeads));
                    }
                    repoMapper.saveSyncCheckpoint(job.getId(),entry.getKey(),encoded);
                    repoMapper.retireOldCheckpoints(input.scope().codingTeamId(),input.scope().scope().name(),
                            input.scope().scopeId(),entry.getKey(),job.getId());
                } catch(java.io.IOException failure) { failed=true;failureCode="CHECKPOINT_TOO_LARGE"; }
            }
            if (failed) markRepoFailed(job, depot, failureCode,
                    "BRANCH_CHANGED".equals(failureCode)
                            ? "有 " + changedBranches + " 个分支待复核，已保留稳定分支进度和成功数据"
                            : "部分分支未同步，已保留稳定分支进度和成功数据",
                    entry.getValue().size(), written);
            else {
                commitMapper.cleanupSyncRepo(input.scope().codingTeamId(), input.scope().scope().name(), input.scope().scopeId(),
                        entry.getKey(), job.getSyncFrom(), job.getSyncTo(), job.getId());
                markRepoCompleted(job, depot, entry.getValue().size(), written);
            }
            log.info("zhaogang continuous repo, jobId={}, depotId={}, stableBranches={}, commitPageCalls={}, partial={}",
                    job.getId(),entry.getKey(),nextBranches.size(),calls.get()-callsBefore,failed);

        }
        log.info("zhaogang continuous sync, jobId={}, skippedHeads={}, commitPageCalls={}", job.getId(), skipped, calls.get());
        job.setStage("DIFFS"); save(job);
        return touched;
    }

    private int logChangedBranches(long jobId, long depotId, Map<String, String> before, Map<String, String> after) {
        Set<String> branches = new LinkedHashSet<>(before.keySet());
        branches.addAll(after.keySet());
        List<String> changed = branches.stream()
                .filter(branch -> !java.util.Objects.equals(before.get(branch), after.get(branch)))
                .toList();
        for (String branch : changed.stream().limit(10).toList()) {
            log.info("zhaogang branch head changed, jobId={}, depotId={}, branch={}, beforeSha={}, afterSha={}, changedBranches={}",
                    jobId, depotId, CodeStatsSupport.truncate(branch.replaceAll("\\p{Cntrl}", "?"), 200),
                    diagnosticSha(before.get(branch)), diagnosticSha(after.get(branch)), changed.size());
        }
        return changed.size();
    }

    private static String diagnosticSha(String sha) {
        if (sha == null) return "-";
        return sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}") ? sha : "INVALID_SHA";
    }

    private Set<String> collectCommits(ScanInput input, ScanJobEntity job, Map<Long, DepotPlan> depots,
                                       Map<Long, List<BranchPlan>> branches) {
        Set<String> touchedMonths = new LinkedHashSet<>();
        String from = input.month().atDay(1).toString();
        LocalDate end = input.month().atEndOfMonth();
        String to = (end.isAfter(LocalDate.now(clock)) ? LocalDate.now(clock) : end).toString();
        java.util.concurrent.atomic.AtomicInteger pageCalls = new java.util.concurrent.atomic.AtomicInteger();
        int reusedBranches = 0;
        int readHeads = 0;
        try (CodeScanReadAhead<BranchRead> readAhead = new CodeScanReadAhead<>(properties.getCodingConcurrencyPerToken())) {
            for (Map.Entry<Long, List<BranchPlan>> entry : branches.entrySet()) {
                if (cancelled(job)) throw new ScanCancelledException();
                DepotPlan depot = depots.get(entry.getKey());
                List<BranchPlan> repoBranches = entry.getValue();
                Map<String, List<BranchPlan>> groups = new LinkedHashMap<>();
                for (BranchPlan branch : repoBranches) {
                    // 空 SHA 不可缓存，且不能把不同未知分支合并为一个读取。
                    String key = snapshotKey(input, branch, to);
                    groups.computeIfAbsent(key == null ? "ref:" + branch.branch() : key, ignored -> new ArrayList<>()).add(branch);
                }
                Map<String, CommitAccumulator> commits = new LinkedHashMap<>();
                Map<String, BranchSnapshot> snapshotsToSave = new LinkedHashMap<>();
                String repoFailure = null;
                List<List<BranchPlan>> groupsToRead = new ArrayList<>();
                for (List<BranchPlan> group : groups.values()) {
                    String key = snapshotKey(input, group.get(0), to);
                    BranchSnapshot snapshot = key == null ? null : cache.get(key, BranchSnapshot.class).orElse(null);
                    if (!validSnapshot(snapshot, from, to)) {
                        groupsToRead.add(group);
                    } else {
                        mergeSnapshot(commits, group, snapshot, input, job);
                        reusedBranches += group.size();
                        job.setDoneBranches(job.getDoneBranches() + group.size());
                    }
                }
                for (int offset = 0; offset < groupsToRead.size(); offset += readAhead.width()) {
                    int limit = Math.min(groupsToRead.size(), offset + readAhead.width());
                    List<List<BranchPlan>> batch = groupsToRead.subList(offset, limit);
                    List<java.util.concurrent.Callable<BranchRead>> reads = batch.stream()
                            .<java.util.concurrent.Callable<BranchRead>>map(group -> () ->
                                    readGroup(input, group, from, to, pageCalls)).toList();
                    List<java.util.concurrent.Future<BranchRead>> pages = readAhead.submit(reads);
                    for (int index = 0; index < batch.size(); index++) {
                        List<BranchPlan> group = batch.get(index);
                        try {
                            BranchRead result = awaitBranch(pages.get(index), job);
                            readHeads++;
                            mergeSnapshot(commits, group, result.snapshot(), input, job);
                            if (result.truncated()) job.setTruncated(true);
                            if (result.errorCode() != null) repoFailure = result.errorCode();
                            String key = snapshotKey(input, group.get(0), to);
                            if (!result.truncated() && result.errorCode() == null && key != null) snapshotsToSave.put(key, result.snapshot());
                            reusedBranches += group.size() - 1;
                        } catch (ScanCancelledException | ScanTaskLostException stopped) {
                            throw stopped;
                        } catch (RuntimeException failure) {
                            repoFailure = failure instanceof CodingOpenApiException error && error.isPermissionDenied()
                                    ? "PERMISSION_DENIED" : "REQUEST_FAILED";
                        }
                        job.setDoneBranches(job.getDoneBranches() + group.size());
                        save(job);
                    }
                }
                if (!java.util.Objects.equals(input.identitySignature(), identities.signature(input.scope()))) {
                    throw new CodingOpenApiException("IDENTITY_CHANGED", "Git 身份已变化，将按新身份重新扫描");
                }
                int written = upsertCommits(commits.values());
                commits.values().stream().map(item -> item.month.toString()).distinct().forEach(touchedMonths::add);
                job.setTotalCommits(job.getTotalCommits() + written);
                job.setDoneCommits(job.getDoneCommits() + written);
                // Ref 在读取期间可能前进或被强推。再次核对分支头后才发布新快照。
                if (!snapshotsToSave.isEmpty()) {
                    try {
                        Map<String, String> after = new HashMap<>();
                        coding.branchHeads(repoBranches.get(0).token(), depot.depot().projectId(), depot.depot().id(), "CODING")
                                .forEach(branch -> after.put(branch.name(), branch.sha()));
                        for (List<BranchPlan> group : groups.values()) {
                            BranchPlan first = group.get(0);
                            String key = snapshotKey(input, first, to);
                            if (key == null || !snapshotsToSave.containsKey(key)) continue;
                            boolean stable = group.stream().allMatch(branch -> branch.headSha().equals(after.get(branch.branch())));
                            if (stable) cache.putBranchSnapshot(key, snapshotsToSave.get(key));
                            else job.setCoverageIncomplete(true);
                        }
                    } catch (RuntimeException verificationFailed) {
                        job.setCoverageIncomplete(true);
                    }
                }
                if (repoFailure != null) markRepoFailed(job, depot, repoFailure, "部分分支提交读取失败", repoBranches.size(), written);
                else markRepoCompleted(job, depot, repoBranches.size(), written);
            }
        }
        log.info("zhaogang code branch cache, jobId={}, reusedBranches={}, readHeads={}, commitPageCalls={}",
                job.getId(), reusedBranches, readHeads, pageCalls.get());
        job.setStage("DIFFS");
        save(job);
        return touchedMonths;
    }

    private ContinuousRead readContinuousHead(ScanInput input, ScanJobEntity job, BranchPlan branch,
                                               CodeBranchSync.Checkpoint before, Map<String, CommitEntity> existing,
                                               java.util.concurrent.atomic.AtomicInteger calls,
                                               Map<String,CodeCommitGraph.Node> graph,Set<String> available) {
        String head=branch.headSha();
        if (head==null || !head.matches("[0-9a-fA-F]{40,64}")) throw new CodingOpenApiException("分支头信息不完整");
        CodeBranchSync.Head previous=before==null || before.branches()==null || before.heads()==null ? null
                : before.heads().get(head);
        if(previous==null && before!=null && before.branches()!=null && before.heads()!=null) {
            String oldHead=before.branches().get(branch.branch());
            if(oldHead!=null) previous=before.heads().get(oldHead);
        }
        if(previous!=null && head.equals(previous.sha()) && !previous.through().equals(job.getSyncTo().toString())
                && (branch.headAtMillis()==null || branch.headAtMillis()>LocalDate.parse(previous.through()).plusDays(1)
                .atStartOfDay(CodeStatsSupport.CHINA_ZONE).toInstant().toEpochMilli()-1)) previous=null;
        if(previous!=null && (previous.matched()==null || previous.reachable()==null
                || !existing.keySet().containsAll(previous.matched()))) previous=null;
        if(previous!=null && head.equals(previous.sha()))
            return new ContinuousRead(new CodeBranchSync.Result(previous,List.of(),true,true),true,Map.of());
        CodeCommitGraph.Read result=CodeCommitGraph.read(head,job.getSyncTo().toString(),graph,available,
                Math.max(1,properties.getCodeCommitPageSize()),Math.max(1,properties.getCodeMaxPagesPerBranch()),(ref,page) -> {
                    if(Thread.currentThread().isInterrupted()) throw new ScanCancelledException();
                    calls.incrementAndGet();
                    return fetchCommitPage(new BranchPlan(branch.plan(),branch.token(),ref,ref,null),
                            job.getSyncFrom().toString(),job.getSyncTo().toString(),page);
                },commit -> {
                    LocalDateTime at=CodeStatsSupport.fromMillis(commit.commitAtMillis());
                    return at!=null && !at.toLocalDate().isBefore(job.getSyncFrom()) && !at.toLocalDate().isAfter(job.getSyncTo())
                            && belongsToScope(input,identities.resolve(input.identityIndex(),
                            CodeStatsSupport.emailHash(commit.authorEmail()),CodeStatsSupport.nameKey(commit.authorName())));
                });
        return new ContinuousRead(result.result(),!result.requested(),result.learned());
    }

    private ContinuousRead awaitContinuous(java.util.concurrent.Future<ContinuousRead> value, ScanJobEntity job) {
        while(true) {
            if(cancelled(job)) throw new ScanCancelledException();
            try { return value.get(1,java.util.concurrent.TimeUnit.SECONDS); }
            catch(java.util.concurrent.TimeoutException waiting) { save(job); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new ScanCancelledException(); }
            catch(java.util.concurrent.ExecutionException failed) {
                if(failed.getCause() instanceof RuntimeException error) throw error;
                throw new CodingOpenApiException("提交增量读取失败");
            }
        }
    }
    private record ContinuousRead(CodeBranchSync.Result result,boolean reused,Map<String,CodeCommitGraph.Node> learned) {}

    private boolean validSnapshot(BranchSnapshot snapshot, String from, String to) {
        return snapshot != null && snapshot.commits() != null && snapshot.commits().stream().allMatch(row ->
                row != null && StringUtils.isNotBlank(row.getSha()) && row.getCommitAt() != null
                        && !row.getCommitAt().toLocalDate().isBefore(LocalDate.parse(from))
                        && !row.getCommitAt().toLocalDate().isAfter(LocalDate.parse(to)));
    }

    private String snapshotKey(ScanInput input, BranchPlan branch, String to) {
        if (branch.headSha() == null || !branch.headSha().matches("[0-9a-fA-F]{40,64}")) return null;
        return "branch-month:v2:" + input.identitySignature() + ":" + cache.scopeKey(input.scope()) + ":" + branch.plan().depot().id()
                + ":" + input.month() + ":" + to + ":" + branch.headSha().toLowerCase(Locale.ROOT);
    }

    private BranchRead readGroup(ScanInput input, List<BranchPlan> group, String from, String to,
                                 java.util.concurrent.atomic.AtomicInteger pageCalls) {
        BranchRead best = null;
        for (int ref = 0; ref < Math.min(3, group.size()); ref++) {
            BranchRead result = readBranch(input, group.get(ref), from, to, pageCalls);
            if (result.errorCode() == null) return result;
            if (best == null || result.snapshot().commits().size() > best.snapshot().commits().size()) best = result;
        }
        return best;
    }

    private BranchRead readBranch(ScanInput input, BranchPlan branch, String from, String to,
                                  java.util.concurrent.atomic.AtomicInteger pageCalls) {
        Map<String, CommitAccumulator> rows = new LinkedHashMap<>();
        int page = 1;
        while (page <= Math.max(1, properties.getCodeMaxPagesPerBranch())) {
            if (Thread.currentThread().isInterrupted()) throw new ScanCancelledException();
            pageCalls.incrementAndGet();
            CodingCommitPage result;
            try {
                result = fetchCommitPage(branch, from, to, page);
            } catch (CodingOpenApiException failure) {
                return new BranchRead(new BranchSnapshot(rows.values().stream().map(CommitAccumulator::toEntity).toList()),
                        false, failure.isPermissionDenied() ? "PERMISSION_DENIED" : "REQUEST_FAILED");
            }
            for (CodingCommit commit : result.items()) {
                LocalDateTime time = CodeStatsSupport.fromMillis(commit.commitAtMillis());
                if (time != null && !time.toLocalDate().isBefore(LocalDate.parse(from))
                        && !time.toLocalDate().isAfter(LocalDate.parse(to))) {
                    CodeIdentityResolver.Resolved owner = identities.resolve(input.identityIndex(),
                            CodeStatsSupport.emailHash(commit.authorEmail()), CodeStatsSupport.nameKey(commit.authorName()));
                    if (belongsToScope(input, owner)) {
                        accumulate(rows, branch.plan(), branch.branch(), commit, input, input.job());
                    }
                }
            }
            if (result.items().size() < Math.max(1, properties.getCodeCommitPageSize())
                    || (result.totalCount() > 0 && (long) page * result.pageSize() >= result.totalCount())) {
                return new BranchRead(new BranchSnapshot(rows.values().stream().map(CommitAccumulator::toEntity).toList()), false, null);
            }
            page++;
        }
        return new BranchRead(new BranchSnapshot(rows.values().stream().map(CommitAccumulator::toEntity).toList()), true, null);
    }

    private void mergeSnapshot(Map<String, CommitAccumulator> target, List<BranchPlan> branches,
                               BranchSnapshot snapshot, ScanInput input, ScanJobEntity job) {
        for (CommitEntity row : snapshot.commits()) {
            if (!belongsToScope(input, identities.resolve(input.identityIndex(), row.getAuthorEmailHash(),
                    row.getAuthorNameKey()))) continue;
            CommitAccumulator accumulator = target.computeIfAbsent(row.getSha(), ignored ->
                    new CommitAccumulator(branches.get(0).plan(), row.getSha(), row.getCommitAt(), input, job.getId()));
            accumulator.authorName = row.getAuthorName();
            accumulator.authorEmailHash = row.getAuthorEmailHash();
            accumulator.authorNameKey = row.getAuthorNameKey();
            accumulator.message = row.getMessage();
            accumulator.shortSha = row.getShortSha();
            accumulator.merge |= Boolean.TRUE.equals(row.getIsMerge());
            branches.forEach(branch -> accumulator.addBranch(branch.branch()));
        }
    }

    private BranchRead awaitBranch(java.util.concurrent.Future<BranchRead> page, ScanJobEntity job) {
        while (true) {
            if (cancelled(job)) throw new ScanCancelledException();
            try {
                return page.get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException waiting) {
                // 等待远端读取时继续检查取消。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ScanCancelledException();
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException error) throw error;
                throw new CodingOpenApiException("提交读取失败，请稍后重试");
            }
        }
    }

    record BranchSnapshot(List<CommitEntity> commits) {}
    private record BranchRead(BranchSnapshot snapshot, boolean truncated, String errorCode) {}

    private CodingCommitPage fetchCommitPage(BranchPlan branch, String startDate, String endDate, int page) {
        CodingCommitQuery query = new CodingCommitQuery(branch.plan().depot().projectId(),
                branch.plan().depot().id(), "CODING", branch.branch(), startDate, endDate, page,
                Math.max(1, properties.getCodeCommitPageSize()));
        List<MemberToken> tokens = new ArrayList<>();
        for (MemberToken token : branch.plan().tokens()) {
            if (token.token().equals(branch.token())) tokens.add(token);
        }
        branch.plan().tokens().stream().filter(token -> !token.token().equals(branch.token())).forEach(tokens::add);
        RuntimeException lastError = null;
        for (MemberToken token : tokens) {
            try {
                return coding.commits(token.token(), query);
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw lastError instanceof CodingOpenApiException codingError
                ? codingError : new CodingOpenApiException("提交读取失败，请稍后重试");
    }

    private void accumulate(Map<String, CommitAccumulator> commits, DepotPlan plan, String branch,
                            CodingCommit commit, ScanInput input, ScanJobEntity job) {
        if (StringUtils.isBlank(commit.sha())) return;
        LocalDateTime commitAt = CodeStatsSupport.fromMillis(commit.commitAtMillis());
        if (commitAt == null) return;
        CommitAccumulator accumulator = commits.computeIfAbsent(commit.sha(),
                ignored -> new CommitAccumulator(plan, commit.sha(), commitAt, input, job.getId()));
        accumulator.authorName = StringUtils.defaultIfBlank(commit.authorName(), accumulator.authorName);
        accumulator.authorEmailHash = accumulator.authorEmailHash != null ? accumulator.authorEmailHash
                : CodeStatsSupport.emailHash(commit.authorEmail());
        accumulator.authorNameKey = StringUtils.defaultIfBlank(accumulator.authorNameKey,
                CodeStatsSupport.nameKey(commit.authorName()));
        accumulator.message = StringUtils.defaultIfBlank(accumulator.message, commit.message());
        accumulator.shortSha = StringUtils.defaultIfBlank(accumulator.shortSha, commit.shortSha());
        accumulator.merge = accumulator.merge || commit.parents().size() > 1;
        accumulator.addBranch(branch);
    }

    private void pruneUnmatched(ScanInput input) {
        List<Long> remove = commitMapper.selectByMonth(input.scope().codingTeamId(), input.scope().scope().name(),
                input.scope().scopeId(), input.month().toString()).stream()
                .filter(row -> !belongsToScope(input, identities.resolve(input.identityIndex(),
                        row.getAuthorEmailHash(), row.getAuthorNameKey())))
                .map(CommitEntity::getId).toList();
        for (int i = 0; i < remove.size(); i += 200) {
            commitMapper.deleteUnmatchedRows(input.scope().codingTeamId(), input.scope().scope().name(),
                    input.scope().scopeId(), input.month().toString(), remove.subList(i, Math.min(remove.size(), i + 200)));
        }
    }

    private boolean belongsToScope(ScanInput input, CodeIdentityResolver.Resolved owner) {
        return owner != null && !owner.ignored() && owner.memberId() != null
                && input.scope().members().stream().anyMatch(member -> member.userId() == owner.memberId());
    }

    private int upsertCommits(Iterable<CommitAccumulator> accumulators) {
        List<CommitEntity> batch = new ArrayList<>();
        int written = 0;
        for (CommitAccumulator accumulator : accumulators) {
            batch.add(accumulator.toEntity());
            if (batch.size() >= 200) {
                commitMapper.upsertBatch(batch);
                written += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            commitMapper.upsertBatch(batch);
            written += batch.size();
        }
        return written;
    }

    private void fetchDiffs(ScanInput input, ScanJobEntity job, Map<Long, DepotPlan> depots,
                            Set<String> touchedMonths) {
        Map<Long, List<String>> depotTokens = new HashMap<>();
        depots.forEach((depotId, plan) -> depotTokens.put(depotId,
                plan.tokens().stream().map(MemberToken::token).toList()));
        Map<Long, MemberToken> memberTokens = new HashMap<>();
        List<String> fallbackTokens = new ArrayList<>();
        input.scope().members().forEach(member -> {
            if (StringUtils.isNotBlank(member.token())) {
                memberTokens.put(member.userId(), new MemberToken(member.userId(), member.token()));
                fallbackTokens.add(member.token());
            }
        });
        List<IdentityEntity> mappings = identities.mappings(input.scope());
        Map<String, IdentityEntity> mappingIndex = identities.mappingIndex(mappings);
        Set<Long> memberIds = new HashSet<>();
        input.scope().members().forEach(member -> memberIds.add(member.userId()));

        List<CommitEntity> pending = new ArrayList<>();
        boolean retryOnly = DIFF_RETRY.equals(input.mode());
        CodeGitLineStats.Batch gitBatch = retryOnly && properties.isCodeGitLineStatsEnabled()
                ? gitLineStats.batch(input.scope()) : null;
        if (retryOnly) {
            Set<Long> selected = Set.copyOf(input.retryCommitIds());
            commitMapper.selectDiffFailures(input.scope().codingTeamId(), input.scope().scope().name(),
                    input.scope().scopeId(), job.getSyncFrom(), job.getSyncTo()).stream()
                    .filter(row -> selected.contains(row.getId())).forEach(pending::add);
        } else {
            for (String month : touchedMonths) {
                commitMapper.applyCommitCountOnlyPolicy(input.scope().codingTeamId(), input.scope().scope().name(),
                        input.scope().scopeId(), month, CodeLinePolicy.COMMIT_COUNT_ONLY_DEPOT);
                pending.addAll(commitMapper.selectPendingDiffs(input.scope().codingTeamId(),
                        input.scope().scope().name(), input.scope().scopeId(), month));
            }
        }
        job.setTotalDiffs(pending.size());
        job.setDoneDiffs(0);
        save(job);
        int processed = 0;
        for (CommitEntity commit : pending) {
            if (cancelled(job)) throw new ScanCancelledException();
            if (!java.util.Objects.equals(input.identitySignature(), identities.signature(input.scope()))) {
                throw new CodingOpenApiException("IDENTITY_CHANGED", "Git 身份已变化，将按新身份重新扫描");
            }
            CodeIdentityResolver.Resolved resolved = identities.resolve(mappingIndex,
                    commit.getAuthorEmailHash(), commit.getAuthorNameKey());
            if (CodeLinePolicy.commitCountOnly(commit.getDepotName())) {
                commitMapper.updateDiff(commit.getId(), null, null, null, CodeLinePolicy.SKIPPED_REPOSITORY, null);
            } else if (resolved.memberId() == null || !memberIds.contains(resolved.memberId())) {
                if (retryOnly) job.setFailedDiffs(job.getFailedDiffs() + 1);
                else commitMapper.updateDiffStatus(commit.getId(), "SKIPPED_UNMAPPED");
            } else {
                List<String> candidates = diffTokens(memberTokens.get(resolved.memberId()),
                        depotTokens.getOrDefault(commit.getDepotId(), fallbackTokens));
                if (candidates.isEmpty()) {
                    if (retryOnly) job.setFailedDiffs(job.getFailedDiffs() + 1);
                    else commitMapper.updateDiffStatus(commit.getId(), "SKIPPED_UNMAPPED");
                } else {
                    fetchDiff(commit, candidates, job, gitBatch);
                }
            }
            processed++;
            job.setDoneDiffs(processed);
            if (retryOnly || processed % 25 == 0) save(job);
        }
        if (!retryOnly && !depots.isEmpty() && input.scope().scope() == Scope.WORKBENCH_TEAM) {
            backfillFileTypes(input, job, mappingIndex, memberTokens, depotTokens);
        }
        job.setStage("AGGREGATING");
        save(job);
    }

    private List<String> diffTokens(MemberToken ownerToken, List<String> repositoryTokens) {
        List<String> candidates = new ArrayList<>();
        if (ownerToken != null) candidates.add(ownerToken.token());
        candidates.addAll(repositoryTokens);
        return candidates;
    }

    private void backfillFileTypes(ScanInput input, ScanJobEntity job, Map<String, IdentityEntity> mappingIndex,
                                   Map<Long, MemberToken> memberTokens, Map<Long, List<String>> depotTokens) {
        List<CommitEntity> historical = commitMapper.selectFileTypesBackfill(input.scope().codingTeamId(),
                input.scope().scopeId());
        Set<Long> developerIds = new HashSet<>();
        input.scope().developmentMembers().forEach(member -> developerIds.add(member.userId()));
        for (CommitEntity commit : historical) {
            if (cancelled(job)) throw new ScanCancelledException();
            var owner = identities.resolve(mappingIndex, commit.getAuthorEmailHash(), commit.getAuthorNameKey());
            List<String> repositoryTokens = depotTokens.get(commit.getDepotId());
            String stats = null;
            if (!owner.ignored() && developerIds.contains(owner.memberId()) && repositoryTokens != null) {
                List<String> tokens = diffTokens(memberTokens.get(owner.memberId()), repositoryTokens);
                stats = readHistoricalFileTypes(commit, tokens);
            }
            commitMapper.updateFileTypesBackfill(commit.getId(), stats);
        }
    }

    private String readHistoricalFileTypes(CommitEntity commit, List<String> tokens) {
        for (String token : new LinkedHashSet<>(tokens)) {
            try {
                CodingCommitDiff diff = coding.commitDiff(token, commit.getProjectId(), commit.getDepotId(), commit.getSha());
                if (!diff.fileTypes().isEmpty()
                        && Long.valueOf(diff.additions()).equals(commit.getAdditions())
                        && Long.valueOf(diff.deletions()).equals(commit.getDeletions())) {
                    return CodeFileTypes.encode(diff.fileTypes());
                }
                return null;
            } catch (CodingOpenApiException e) {
                if (Thread.currentThread().isInterrupted()) throw new ScanCancelledException();
                if (CodeDiffFailure.classify(e) != CodeDiffFailure.PERMISSION_DENIED) return null;
            }
        }
        return null;
    }

    private void fetchDiff(CommitEntity commit, List<String> candidates, ScanJobEntity job, CodeGitLineStats.Batch gitBatch) {
        Set<DiffAttempt> failures = new LinkedHashSet<>();
        String gitCandidate = null;
        for (String token : new LinkedHashSet<>(candidates)) {
            CodingCommitDiff diff;
            try {
                diff = coding.commitDiff(token, commit.getProjectId(),
                        commit.getDepotId(), commit.getSha());
            } catch (CodingOpenApiException error) {
                CodeDiffFailure reason = CodeDiffFailure.classify(error);
                failures.add(new DiffAttempt(reason, CodeDiffFailure.safeUpstreamCode(error),
                        error.httpStatus(), error instanceof CodingResponseTooLargeException large ? large.limitBytes() : null));
                if (gitBatch != null && gitCandidate == null && isGitFallbackCandidate(reason, error.httpStatus())) {
                    gitCandidate = token;
                }
                if (Thread.currentThread().isInterrupted()) throw new ScanCancelledException();
                continue;
            }
            storeDiff(commit, diff);
            return;
        }
        CodeGitLineStats.Failure gitFailure = null;
        if (gitCandidate != null) {
            if (cancelled(job)) throw new ScanCancelledException();
            CodeGitLineStats.Result git = gitBatch.count(commit, gitCandidate);
            if (cancelled(job) || Thread.currentThread().isInterrupted()) throw new ScanCancelledException();
            if (git.stats() != null) {
                CodingCommitDiff stats = git.stats();
                storeDiff(commit, stats);
                log.info("zhaogang code diff filled, jobId={}, projectId={}, depotId={}, sha={}, source=GIT, additions={}, deletions={}, files={}",
                        job.getId(), commit.getProjectId(), commit.getDepotId(), diagnosticSha(commit.getSha()),
                        stats.additions(), stats.deletions(), stats.filesChanged());
                return;
            }
            gitFailure = git.failure();
        }
        CodeDiffFailure failure = failures.stream().map(DiffAttempt::reason)
                .min(java.util.Comparator.comparingInt(CodeScanWorker::diffFailurePriority))
                .orElse(CodeDiffFailure.REQUEST_FAILED);
        commitMapper.updateDiff(commit.getId(), null, null, null, failure.status(), null);
        log.info("zhaogang code diff failed, jobId={}, projectId={}, depotId={}, sha={}, reason={}, gitFallback={}, attempts={}",
                job.getId(), commit.getProjectId(), commit.getDepotId(), diagnosticSha(commit.getSha()),
                failure.name(), gitFailure, failures.stream().limit(8).toList());
        job.setFailedDiffs(job.getFailedDiffs() + 1);
    }

    private void storeDiff(CommitEntity commit, CodingCommitDiff diff) {
        commitMapper.updateDiff(commit.getId(), (long) diff.additions(), (long) diff.deletions(), diff.filesChanged(),
                "OK", diff.fileTypes().isEmpty() ? null : CodeFileTypes.encode(diff.fileTypes()));
    }

    private static boolean isGitFallbackCandidate(CodeDiffFailure reason, Integer httpStatus) {
        if (!reason.sizeLimit && reason != CodeDiffFailure.INVALID_ARGUMENT) return false;
        if (httpStatus == null || (httpStatus >= 200 && httpStatus < 300)) return true;
        return (reason == CodeDiffFailure.INVALID_ARGUMENT && (httpStatus == 400 || httpStatus == 422))
                || (reason == CodeDiffFailure.UPSTREAM_LIMIT && httpStatus == 413);
    }

    private static int diffFailurePriority(CodeDiffFailure reason) {
        return switch (reason) {
            case UPSTREAM_LIMIT, RESPONSE_LIMIT -> 0;
            case INVALID_ARGUMENT, INVALID_RESPONSE -> 1;
            case RATE_LIMITED, TIMEOUT, UNAVAILABLE -> 2;
            case PERMISSION_DENIED -> 3;
            case REQUEST_FAILED -> 4;
        };
    }

    private record DiffAttempt(CodeDiffFailure reason, String upstreamCode, Integer httpStatus, Long limitBytes) {
    }

    private void aggregate(ScanInput input, ScanJobEntity job) {
        if (!java.util.Objects.equals(input.identitySignature(), identities.signature(input.scope()))) {
            throw new CodingOpenApiException("IDENTITY_CHANGED", "Git 身份已变化，将按新身份重新扫描");
        }
        if (("FULL".equals(input.mode()) || "SYNC".equals(input.mode())) && !job.isCoverageIncomplete()
                && (job.getFailedRepos() == null || job.getFailedRepos() == 0)
                && ("SYNC".equals(input.mode()) || job.getFailedDiffs() == null || job.getFailedDiffs() == 0)
                && !Boolean.TRUE.equals(job.getTruncated())) {
            YearMonth first="SYNC".equals(input.mode()) ? YearMonth.from(job.getSyncFrom()) : input.month();
            YearMonth last="SYNC".equals(input.mode()) ? YearMonth.from(job.getSyncTo()) : input.month();
            for(YearMonth month=first;!month.isAfter(last);month=month.plusMonths(1)) {
                commitMapper.cleanupFullScan(input.scope().codingTeamId(), input.scope().scope().name(),
                        input.scope().scopeId(), month.toString(), job.getId());
            }
        }
        boolean partial = job.isCoverageIncomplete() || (job.getFailedRepos() != null && job.getFailedRepos() > 0)
                || (job.getFailedDiffs() != null && job.getFailedDiffs() > 0)
                || Boolean.TRUE.equals(job.getTruncated());
        finish(job, partial ? "PARTIAL" : "COMPLETED", partial ? "PARTIAL_SCAN" : null,
                partial ? DIFF_RETRY.equals(input.mode()) ? "部分提交仍无法统计行数"
                        : "扫描已结束，部分仓库、分支或提交行数待补齐" : null);
    }

    private void markRepoCompleted(ScanJobEntity job, DepotPlan plan, int branchCount, int commitCount) {
        if (plan == null) return;
        repoMapper.updateState(job.getId(), plan.depot().id(), "COMPLETED", branchCount, commitCount,
                null, null);
    }

    private void markRepoFailed(ScanJobEntity job, DepotPlan plan, String code, String message) {
        markRepoFailed(job, plan, code, message, 0, 0);
    }

    private void markRepoFailed(ScanJobEntity job, DepotPlan plan, String code, String message, int branchCount,
                                int commitCount) {
        repoMapper.updateState(job.getId(), plan.depot().id(), "FAILED", branchCount, commitCount, code, message);
        job.setFailedRepos(job.getFailedRepos() + 1);
        save(job);
    }

    private void save(ScanJobEntity job) {
        job.setHeartbeatAt(LocalDateTime.now(clock));
        if (jobMapper.updateProgress(job) == 0) {
            throw new ScanTaskLostException();
        }
    }

    private void finish(ScanJobEntity job, String status, String errorCode, String errorMessage) {
        ScanJobEntity current = jobMapper.selectById(job.getId());
        if (current != null && ("CANCELLED".equals(current.getStatus())
                || "INTERRUPTED".equals(current.getStatus()))) {
            job.setStatus(current.getStatus());
            job.setStage("DONE");
            job.setActiveKey(null);
            job.setFinishedAt(current.getFinishedAt() == null ? LocalDateTime.now(clock) : current.getFinishedAt());
            job.setErrorCode(current.getErrorCode());
            job.setErrorMessage(current.getErrorMessage());
            job.setHeartbeatAt(LocalDateTime.now(clock));
            jobMapper.updateFinal(job);
            return;
        }
        job.setStatus(status);
        job.setStage("DONE");
        job.setFinishedAt(LocalDateTime.now(clock));
        job.setErrorCode(errorCode);
        job.setErrorMessage(CodeStatsSupport.truncate(errorMessage, 512));
        job.setActiveKey(null);
        job.setHeartbeatAt(LocalDateTime.now(clock));
        jobMapper.updateFinal(job);
        try {
            if (DIFF_RETRY.equals(job.getScanMode())) {
                cache.bumpScanVersion(job.getCodingTeamId() + ":" + job.getScopeType() + ":" + job.getScopeId(), "diff-retry");
            } else if ("SYNC".equals(job.getScanMode()) || "SYNC_V2".equals(job.getScanMode())) {
                for (YearMonth m = YearMonth.from(job.getSyncFrom()); !m.isAfter(YearMonth.from(job.getSyncTo())); m = m.plusMonths(1))
                    cache.bumpScanVersion(job.getCodingTeamId() + ":" + job.getScopeType() + ":" + job.getScopeId(), m.toString());
            } else cache.bumpScanVersion(job.getCodingTeamId() + ":" + job.getScopeType() + ":" + job.getScopeId(), job.getMonth());
        } catch (RuntimeException ignored) {
            // 缓存版本失败不回滚已完成的扫描结果。
        }
        log.info("zhaogang code scan finished, jobId={}, status={}, repos={}, commits={}, diffs={}",
                job.getId(), status, job.getDoneRepos(), job.getDoneCommits(), job.getDoneDiffs());
    }

    private boolean cancelled(ScanJobEntity job) {
        if (cache.cancelled(job.getId())) return true;
        long now = System.currentTimeMillis();
        if (now < nextDbCancelCheckMillis) return false;
        nextDbCancelCheckMillis = now + 10_000L;
        ScanJobEntity current = jobMapper.selectById(job.getId());
        return current == null || "CANCELLED".equals(current.getStatus());
    }

    private String errorCode(CodingOpenApiException error) {
        if (error.isPermissionDenied()) return "PERMISSION_DENIED";
        if (error.isTransportFailure()) return "REQUEST_FAILED";
        return StringUtils.defaultIfBlank(error.code(), "CODING_ERROR");
    }

    private String safeMessage(CodingOpenApiException error) {
        String message = error.isPermissionDenied() ? error.permissionMessage() : error.getMessage();
        return CodeStatsSupport.truncate(StringUtils.defaultIfBlank(message, "代码扫描失败"), 512);
    }

    record ScanInput(ScanJobEntity job, CodeStatsModule.ScopeInfo scope, YearMonth month, String mode,
                     long requestedBy, Map<String, IdentityEntity> identityIndex, String identitySignature,
                     List<Long> retryCommitIds) {
        ScanInput(ScanJobEntity job, CodeStatsModule.ScopeInfo scope, YearMonth month, String mode, long requestedBy) {
            this(job, scope, month, mode, requestedBy, Map.of(), "", List.of());
        }
    }

    private record MemberToken(long userId, String token) {
    }

    private record DepotPlan(CodingDepot depot, List<MemberToken> tokens) {
    }

    private record BranchPlan(DepotPlan plan, String token, String branch, String headSha, Long headAtMillis) {
    }

    private static final class CommitAccumulator {
        private final DepotPlan plan;
        private final String sha;
        private final LocalDateTime commitAt;
        private final YearMonth month;
        private final long codingTeamId;
        private final String scopeType;
        private final long scopeId;
        private final long jobId;
        private final Set<String> branches = new LinkedHashSet<>();
        private final Set<String> branchesSeen = new HashSet<>();
        private String shortSha = "";
        private String authorName = "";
        private String authorEmailHash;
        private String authorNameKey = "";
        private String message = "";
        private boolean merge;

        private CommitAccumulator(DepotPlan plan, String sha, LocalDateTime commitAt, ScanInput input, long jobId) {
            this.plan = plan;
            this.sha = sha;
            this.commitAt = commitAt;
            this.month = YearMonth.from(commitAt);
            this.codingTeamId = input.scope().codingTeamId();
            this.scopeType = input.scope().scope().name();
            this.scopeId = input.scope().scopeId();
            this.jobId = jobId;
        }

        private void addBranch(String branch) {
            if (branchesSeen.add(branch) && branches.size() < CodeStatsSupport.MAX_BRANCH_NAMES) {
                branches.add(branch);
            }
        }

        private CommitEntity toEntity() {
            CommitEntity entity = new CommitEntity();
            entity.setCodingTeamId(codingTeamId);
            entity.setScopeType(scopeType);
            entity.setScopeId(scopeId);
            entity.setMonth(month.toString());
            entity.setProjectId(plan.depot().projectId());
            entity.setProjectName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().projectName()), 128));
            entity.setProjectDisplayName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().projectDisplayName()), 128));
            entity.setDepotId(plan.depot().id());
            entity.setDepotName(CodeStatsSupport.truncate(StringUtils.defaultString(plan.depot().name()), 128));
            entity.setSha(sha);
            entity.setShortSha(CodeStatsSupport.truncate(StringUtils.defaultIfBlank(shortSha, sha.length() > 7 ? sha.substring(0, 7) : sha), 12));
            entity.setAuthorName(CodeStatsSupport.truncate(authorName, 128));
            entity.setAuthorEmailHash(authorEmailHash);
            entity.setAuthorNameKey(authorNameKey);
            entity.setCommitAt(commitAt);
            entity.setCommitDate(commitAt.toLocalDate());
            entity.setMessage(CodeStatsSupport.truncate(message, 1024));
            entity.setBranchNames(CodeStatsSupport.toBranchJson(branchesSeen.stream().sorted()
                    .limit(CodeStatsSupport.MAX_BRANCH_NAMES).toList()));
            entity.setBranchCount(branchesSeen.size());
            entity.setBranchTruncated(branchesSeen.size() > CodeStatsSupport.MAX_BRANCH_NAMES);
            entity.setAdditions(null);
            entity.setDeletions(null);
            entity.setFilesChanged(null);
            entity.setDiffStatus(merge ? "SKIPPED_MERGE" : "PENDING");
            entity.setIsMerge(merge);
            entity.setCommitUrl(CodeStatsSupport.truncate(CodeStatsSupport.commitUrl(plan.depot().webUrl(), sha), 768));
            entity.setLastSeenScanId(jobId);
            CodeLinePolicy.apply(entity);
            return entity;
        }
    }

    private static final class ScanCancelledException extends RuntimeException {
    }

    private static final class ScanTaskLostException extends RuntimeException {
    }
}
