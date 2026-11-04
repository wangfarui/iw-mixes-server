package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommit;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;

import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** 持久分支边界。只保存 SHA，不保存未命中作者的姓名/邮箱或提交正文。 */
final class CodeBranchSync {
    record Head(String sha, String through, Set<String> reachable, Set<String> matched) {}
    record Checkpoint(Map<String,String> branches,Map<String,Head> heads,Map<String,CodeCommitGraph.Node> graph) {
        Checkpoint(Map<String,String> branches,Map<String,Head> heads) { this(branches,heads,Map.of()); }
    }
    record Result(Head head, List<CodingCommit> added, boolean complete, boolean incremental, String errorCode) {
        Result(Head head, List<CodingCommit> added, boolean complete, boolean incremental) {
            this(head,added,complete,incremental,null);
        }
    }

    static Result read(String sha, String through, Head previous, int pageSize, int maxPages,
                       IntFunction<CodingCommitPage> fetch, Predicate<CodingCommit> matches) {
        // 对不明、损坏的游标回退完整读取；不能仅凭“遇到一条旧 SHA”停止，合并还有其他父链。
        if (previous != null && previous.reachable() != null && previous.matched() != null
                && previous.reachable().contains(previous.sha())) {
            Result delta = readPages(sha, through, previous, pageSize, maxPages, fetch, matches);
            // 若边界无法闭合，readPages 已完整读取至末页（或报告截断），不能再次重复拉取。
            return delta;
        }
        return readPages(sha, through, null, pageSize, maxPages, fetch, matches);
    }

    private static Result readPages(String sha, String through, Head previous, int pageSize, int maxPages,
                                    IntFunction<CodingCommitPage> fetch, Predicate<CodingCommit> matches) {
        Set<String> known = previous == null ? Set.of() : previous.reachable();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> required = new HashSet<>(Set.of(sha));
        Set<String> matched = new HashSet<>();
        List<CodingCommit> added = new ArrayList<>();
        boolean oldHeadReached = false;
        for (int page = 1; page <= maxPages; page++) {
            CodingCommitPage value;
            try { value=fetch.apply(page); }
            catch(CodingOpenApiException failure) {
                return new Result(head(sha,through,seen,matched),added,false,false,
                        failure.isPermissionDenied()?"PERMISSION_DENIED":"REQUEST_FAILED");
            }
            for (CodingCommit commit : value.items()) {
                if (commit.sha() == null || commit.sha().isBlank()) continue;
                seen.add(commit.sha());
                required.remove(commit.sha());
                if (previous != null && commit.sha().equals(previous.sha())) oldHeadReached = true;
                if (!known.contains(commit.sha())) {
                    for (String parent : commit.parents()) {
                        if (previous != null && parent.equals(previous.sha())) oldHeadReached = true;
                        if (!seen.contains(parent) && !known.contains(parent)) required.add(parent);
                    }
                    if (matches.test(commit)) { matched.add(commit.sha()); added.add(commit); }
                }
            }
            if (previous != null && oldHeadReached && required.isEmpty()) {
                seen.addAll(known);
                matched.addAll(previous.matched());
                return new Result(head(sha, through, seen, matched), added, true, true);
            }
            if (value.items().size() < pageSize
                    || (value.totalCount() > 0 && (long) page * value.pageSize() >= value.totalCount())) {
                if (previous != null) previous.matched().stream().filter(seen::contains).forEach(matched::add);
                return new Result(head(sha, through, seen, matched), added, true, false);
            }
        }
        return new Result(head(sha, through, seen, matched), added, false, false);
    }

    private static Head head(String sha, String through, Set<String> seen, Set<String> matched) {
        // 边界只需最近一段已确认 SHA；完整命中集合用于重建归属和分支。限制游标体积，避免复制全部作者历史。
        Set<String> frontier = new LinkedHashSet<>();
        if (seen.contains(sha)) frontier.add(sha);
        seen.stream().limit(256).forEach(frontier::add);
        return new Head(sha, through, frontier, matched);
    }

    private CodeBranchSync() {}
}
