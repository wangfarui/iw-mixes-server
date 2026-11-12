package com.itwray.iw.external.zhaogang.codestats;

import java.util.List;

public final class CodeStatsModels {

    private CodeStatsModels() {
    }

    public enum Scope {
        SELF,
        WORKBENCH_TEAM
    }

    public record AutomaticSyncTarget(Scope scope, long codingTeamId, long scopeId) {
    }

    public record TeamOption(long id, String name, int memberCount, boolean administrator) {
    }

    public record RescanPolicy(int currentMonthCooldownSeconds, int historicalMonthCooldownSeconds,
                               int failedRetrySeconds, int perUserCooldownSeconds) {
    }

    public record Options(List<TeamOption> teams, RescanPolicy rescanPolicy, int myIdentityCount,
                          String serverTime) {
    }

    public record Range(String from, String to, int days, List<String> months) {
        public Range {
            months = months == null ? List.of() : List.copyOf(months);
        }
    }

    public record ScanProgress(int reposDone, int reposTotal, int branchesDone, int branchesTotal,
                               int commitsDone, int commitsTotal, int diffsDone, int diffsTotal) {
    }

    public record ScanMonth(String month, Long jobId, String mode, String status, String stage,
                            ScanProgress progress, String startedAt, String finishedAt, String lastSuccessfulAt,
                            String cooldownUntil, long retryAfterSeconds, String error) {
    }

    public record SyncInfo(boolean enabled, String from, String to, String lastFinishedAt,
                           String nextSyncAt, String status, long retryAfterSeconds, Long jobId,
                           int failedRepoCount, long unavailableDiffCount) {
        public SyncInfo(boolean enabled, String from, String to, String lastFinishedAt,
                        String nextSyncAt, String status, long retryAfterSeconds) {
            this(enabled, from, to, lastFinishedAt, nextSyncAt, status, retryAfterSeconds, null, 0, 0);
        }
    }

    public record ScanState(String scope, Long workbenchTeamId, Range range, String aggregateStatus,
                            List<ScanMonth> months, long retryAfterSeconds, SyncInfo sync) {
        public ScanState(String scope, Long workbenchTeamId, Range range, String aggregateStatus,
                         List<ScanMonth> months, long retryAfterSeconds) {
            this(scope, workbenchTeamId, range, aggregateStatus, months, retryAfterSeconds, null);
        }
        public ScanState {
            months = months == null ? List.of() : List.copyOf(months);
        }
    }

    public record User(long id, String name, String avatar) {
    }

    public record MemberIssue(User user, String reasonCode, String reason) {
    }

    public record RepoIssue(String projectName, String depotName, String reasonCode, String reason) {
    }

    public record Coverage(String scope, Long workbenchTeamId, int memberCount, int patMemberCount,
                           int repoCount, int branchCount, boolean partial, boolean truncated,
                           int identityConflictCount, Integer unmatchedCommitCount, int failedDiffCount,
                           double diffCoverage, long excludedMergeCommitCount, List<MemberIssue> memberIssues,
                           List<RepoIssue> repoIssues) {
        public Coverage {
            memberIssues = memberIssues == null ? List.of() : List.copyOf(memberIssues);
            repoIssues = repoIssues == null ? List.of() : List.copyOf(repoIssues);
        }
    }

    public record SelfSummary(long commitCount, long additions, long deletions, long netLines, int activeDays,
                              int repoCount, int branchCount) {
    }

    public record MemberMonthly(User user, long commitCount, long additions, long deletions, long netLines,
                                int activeDays, int repoCount, int branchCount, String lastCommitAt) {
    }

    public record Daily(String date, int commitCount, long additions, long deletions, long netLines,
                        String dayType) {
    }

    public record Hourly(int hour, int commitCount) {
    }

    public record Repository(long depotId, String projectName, String projectDisplayName, String depotName,
                             long commitCount, long additions, long deletions, int branchCount,
                             String lastCommitAt, boolean lineStatsExcluded) {
    }

    public record Branch(String depotName, String branchName, long commitCount, String lastCommitAt) {
    }

    public record TeamStatistics(boolean dataReady, boolean refreshing, Range range, ScanState scan,
                                 Coverage coverage, List<MemberMonthly> members) {
        public TeamStatistics {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public record SelfStatistics(boolean dataReady, boolean refreshing, Range range, ScanState scan,
                                 Coverage coverage, SelfSummary summary, List<Daily> daily, List<Hourly> hourly,
                                 List<Repository> repositories, List<Branch> branches) {
        public SelfStatistics {
            daily = daily == null ? List.of() : List.copyOf(daily);
            hourly = hourly == null ? List.of() : List.copyOf(hourly);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    public record FileTypeChange(String type, long additions, long deletions) {
        public long changedLines() {
            return additions + deletions;
        }
    }

    public record CommitChange(String sha, String depotName, long depotId, String commitAt, String message,
                               long additions, long deletions, int filesChanged, String commitUrl) {
    }

    public record MemberStatistics(User member, SelfSummary summary, List<Daily> daily,
                                   List<FileTypeChange> fileTypes, List<CommitChange> commitChanges) {
        public MemberStatistics {
            daily = daily == null ? List.of() : List.copyOf(daily);
            fileTypes = fileTypes == null ? List.of() : List.copyOf(fileTypes);
            commitChanges = commitChanges == null ? List.of() : List.copyOf(commitChanges);
        }
    }

    public record Page(int pageNumber, int pageSize, long totalRow, int totalPage) {
    }

    public enum EntrySort { COMMIT_AT, ADDITIONS, DELETIONS, FILES_CHANGED }

    public enum SortDirection { ASC, DESC }

    public record Entry(long depotId, String sha, String shortSha, String commitAt, String projectName,
                        String projectDisplayName, String depotName, List<String> branches, int branchCount,
                        boolean branchTruncated, String message, Long additions, Long deletions,
                        Integer filesChanged, String diffStatus, String commitUrl) {
        public Entry {
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    public record EntryPage(Range range, Page page, List<Entry> items, List<Repository> repositories) {
        public EntryPage {
            items = items == null ? List.of() : List.copyOf(items);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }
    }

    public record SkippedMonth(String month, String reason, long retryAfterSeconds) {
    }

    public record ScanTrigger(boolean accepted, String reason, List<SkippedMonth> skippedMonths,
                              long retryAfterSeconds, ScanState scan) {
        public ScanTrigger {
            skippedMonths = skippedMonths == null ? List.of() : List.copyOf(skippedMonths);
        }
    }

    public record RepoFailure(String projectName, String projectDisplayName, String depotName, String reasonCode,
                              String reason) {
    }

    public record DiffFailure(String projectName, String depotName, String sha, String authorName,
                              String reasonCode, String reason, String commitAt, Long depotId) {
        public DiffFailure(String projectName, String depotName, String sha, String authorName,
                           String reasonCode, String reason, String commitAt) {
            this(projectName, depotName, sha, authorName, reasonCode, reason, commitAt, null);
        }
        public DiffFailure(String projectName, String depotName, String sha, String authorName,
                           String reasonCode, String reason) {
            this(projectName, depotName, sha, authorName, reasonCode, reason, null);
        }
    }

    public record DiffRetryTarget(
            @jakarta.validation.constraints.Positive long depotId,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{40}|[0-9a-fA-F]{64}") String sha) {
    }

    public record DiffRetryRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min = 1, max = 100)
            List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid DiffRetryTarget> targets) {
    }

    public record DiffRetryJob(long jobId, String status, int total, int done, int failed, String error) {
    }

    public record Failures(List<RepoFailure> repoFailures, List<DiffFailure> diffFailures, Page page,
                           String from, String to) {
        public Failures(List<RepoFailure> repoFailures, List<DiffFailure> diffFailures, Page page) {
            this(repoFailures, diffFailures, page, null, null);
        }
        public Failures {
            repoFailures = repoFailures == null ? List.of() : List.copyOf(repoFailures);
            diffFailures = diffFailures == null ? List.of() : List.copyOf(diffFailures);
        }
    }

    public record IdentityMapping(long id, String identityType, String displayValue, String authorName,
                                  User member, String source, boolean ignored, String updatedAt) {
    }

    public record Candidate(String identityKey, String identityType, String authorName, long commitCount,
                            int repoCount, String lastCommitAt, User suggestedMember) {
    }

    public record IdentityPage(List<IdentityMapping> mappings, List<Candidate> candidates, Page page) {
        public IdentityPage {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record ScanRequest(String scope, Long workbenchTeamId, String from, String to, String mode) {
    }

    public record CreateIdentityRequest(String scope, Long workbenchTeamId, String identityType, String identityKey,
                                        Long codingUserId, Boolean ignored, String identityValue, String authorName) {
    }

    public record UpdateIdentityRequest(Long codingUserId, Boolean ignored) {
    }
}
